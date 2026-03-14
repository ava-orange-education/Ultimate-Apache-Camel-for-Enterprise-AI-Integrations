package com.aibook.routes.rag;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.rag.processors.ContextAssembler;
import com.aibook.routes.rag.processors.RagPromptAssembler;
import com.aibook.routes.rag.processors.RagResponseParser;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RAG assembly route — builds context, calls the LLM, parses the answer, and persists output.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   REST POST /api/rag/query → direct:embedQuery (EmbeddingPipelineRoute)
 *     → direct:retrieveKnowledge (QueryRetrievalRoute)
 *       → direct:assembleRagContext
 *           → contextAssembler      (builds RagContext from chunks)
 *           → ragPromptAssembler    (fills rag-answer.txt with {{context}} + {{question}})
 *           → LLM call              (via LlmGateway)
 *           → ragResponseParser     (wraps answer into final RagContext)
 *           → auditArtifactCollector
 *           → direct:ragOutput
 *       → direct:ragOutput
 *           → marshal to JSON
 *           → file:output/rag (queryId-answer.json)
 * </pre>
 */
@Component
public class RagAssemblyRoute extends RouteBuilder {

    @Value("${aibook.rag.output-dir:${java.io.tmpdir}/aibook/rag}")
    private String outputDir;

    @Value("${aibook.llm.model-name:llama3.2}")
    private String modelName;

    private final ContextAssembler       contextAssembler;
    private final RagPromptAssembler     ragPromptAssembler;
    private final RagResponseParser      ragResponseParser;
    private final LlmGateway            llmGateway;
    private final AuditArtifactCollector auditCollector;
    private final AiErrorHandler         aiErrorHandler;

    public RagAssemblyRoute(ContextAssembler contextAssembler,
                            RagPromptAssembler ragPromptAssembler,
                            RagResponseParser ragResponseParser,
                            LlmGateway llmGateway,
                            AuditArtifactCollector auditCollector,
                            AiErrorHandler aiErrorHandler) {
        this.contextAssembler    = contextAssembler;
        this.ragPromptAssembler  = ragPromptAssembler;
        this.ragResponseParser   = ragResponseParser;
        this.llmGateway          = llmGateway;
        this.auditCollector      = auditCollector;
        this.aiErrorHandler      = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(AiGatewayException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "RagAssemblyRoute: LLM call failed [queryId=${header.queryId}]: "
                     + "${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "RagAssemblyRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST query entry point ────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/rag")
                .post("/query")
                    .description("Submit a natural-language query to the RAG pipeline")
                    .consumes("text/plain,application/json")
                    .produces("application/json")
                    .to("direct:embedQuery");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:assembleRagContext
        // Input:  body List<String> chunks + headers from retrieval stage
        // Output: marshalled RagContext JSON → file
        // ═════════════════════════════════════════════════════════════════════
        from("direct:assembleRagContext")
                .routeId("rag-assembly")
                .log(LoggingLevel.INFO,
                     "RagAssemblyRoute: assembling context from ${header.retrievedCount} chunks")

                // Build the RagContext record and assign queryId
                .process(contextAssembler)
                .log(LoggingLevel.INFO,
                     "RagAssemblyRoute: context assembled queryId=${header.queryId}")

                // Load and fill the rag-answer.txt prompt; stash RagContext in header
                .process(ragPromptAssembler)
                .log(LoggingLevel.DEBUG,
                     "RagAssemblyRoute: prompt assembled, calling LLM")

                // Call LLM: system = instruction persona, user = filled RAG prompt
                // Circuit breaker guards against Ollama/LLM unavailability
                .circuitBreaker()
                    .resilience4jConfiguration()
                        .slidingWindowSize(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(30000)
                        .end()
                    .process(exchange -> {
                        String filledPrompt = exchange.getIn().getBody(String.class);
                        String answer = llmGateway.chat(
                                "You are a factual question-answering assistant. "
                                + "Answer only from the provided context. "
                                + "Do not speculate beyond what is stated.",
                                filledPrompt);
                        exchange.getIn().setBody(answer);
                    })
                .onFallback()
                    .process(exchange -> {
                        exchange.getIn().setBody(
                                "LLM temporarily unavailable. Please retry shortly.");
                        exchange.getIn().setHeader("circuitBreakerFallback", true);
                    })
                .end()

                // Parse the LLM answer into a final RagContext
                .process(ragResponseParser)
                .log(LoggingLevel.INFO,
                     "RagAssemblyRoute: answer generated for queryId=${header.queryId} "
                     + "chunks=${header.retrievedCount}")

                // Set audit properties
                .setProperty("stage",        constant("rag-assembly"))
                .setProperty("decisionId",   header("queryId"))
                .setProperty("modelVersion", constant(modelName))

                // Capture audit record
                .process(auditCollector)

                .to("direct:ragOutput");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:ragOutput
        // Marshals the RagContext to JSON and writes to file output directory.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ragOutput")
                .routeId("rag-output")
                .log(LoggingLevel.INFO,
                     "RagAssemblyRoute: persisting answer for queryId=${header.queryId}")

                // Marshal RagContext record to JSON
                .marshal().json()

                // Write to output file: {queryId}-answer.json
                .to("file:" + outputDir + "?fileName=${header.queryId}-answer.json")

                .log(LoggingLevel.INFO,
                     "RagAssemblyRoute: answer saved to "
                     + outputDir + "/${header.queryId}-answer.json")

                // Set a clean, small HTTP-safe response body so Camel's servlet binding
                // does not try to flush the large marshalled JSON into HTTP response headers.
                // Also remove internal pipeline headers (queryVector is a 384-float array
                // that would overflow the HTTP response header buffer when Camel copies all
                // exchange headers to the servlet response).
                .setHeader("Content-Type", constant("application/json"))
                .setBody(simple(
                        "{\"status\":\"accepted\",\"queryId\":\"${header.queryId}\","
                        + "\"chunks\":${header.retrievedCount}}"))
                .removeHeaders("queryVector|relevanceScores|originalQuery|queryId|retrievedCount"
                        + "|CamelHttpUri|CamelHttpUrl|CamelHttpPath|CamelServletContextPath"
                        + "|CamelHttpQuery|breadcrumbId");
    }
}