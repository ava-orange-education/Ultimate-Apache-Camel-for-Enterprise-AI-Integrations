package com.aibook.routes.summarization;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.core.dto.SummaryResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.SummaryValidator;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Core summarization routes — assembles multi-doc context, calls the LLM,
 * validates the response, and routes to output or fallback.
 *
 * <h3>Route overview</h3>
 * <ol>
 *   <li>{@code direct:prepareForSummarization} — builds structured context,
 *       assembles the LLM prompt, calls {@link LlmGateway}, wraps the response
 *       in a {@link SummaryResult}, then validates it.</li>
 *   <li>{@code direct:summaryFallback} — handles validation failures: logs the
 *       reason, forwards to dead-letter for regression capture.</li>
 * </ol>
 */
@Component
public class SummarizationRoute extends RouteBuilder {

    @Value("${aibook.llm.model-name:llama3.2}")
    private String modelName;

    private final LlmGateway              llmGateway;
    private final MultiDocContextBuilder   multiDocContextBuilder;
    private final PromptAssembler          promptAssembler;
    private final SummaryValidator         summaryValidator;
    private final AiErrorHandler           aiErrorHandler;

    public SummarizationRoute(LlmGateway llmGateway,
                               MultiDocContextBuilder multiDocContextBuilder,
                               PromptAssembler promptAssembler,
                               SummaryValidator summaryValidator,
                               AiErrorHandler aiErrorHandler) {
        this.llmGateway            = llmGateway;
        this.multiDocContextBuilder = multiDocContextBuilder;
        this.promptAssembler        = promptAssembler;
        this.summaryValidator       = summaryValidator;
        this.aiErrorHandler         = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(AiGatewayException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "LLM call failed [correlationId=${header.correlationId}]: ${exception.message}")
                .setHeader("validationFailed",        constant(true))
                .setHeader("validationFailureReason", constant("llm_gateway_error"))
                .to("direct:summaryFallback");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "SummarizationRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:prepareForSummarization
        // Receives the aggregated thread/document context string, builds the
        // LLM prompt, calls the LLM, wraps in SummaryResult, validates.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:prepareForSummarization")
                .routeId("summarization-main")
                .log("Starting summarization: correlationId=${header.correlationId} " +
                     "sourceType=${header.SourceType}")

                // Step 1: Build multi-doc structural context from aggregated body
                .process(multiDocContextBuilder)
                .log("Context built: ${header.contextCharCount} chars")

                // Step 2: Load and fill prompt template
                .process(promptAssembler)
                .log("Prompt assembled: template=${header.PromptTemplate}")

                // Step 3: Call LLM — system prompt from header, user message from body
                .process(exchange -> {
                    String systemPrompt = exchange.getIn().getHeader(
                            "promptSystemMessage", String.class);
                    String userMessage  = exchange.getIn().getBody(String.class);
                    String correlationId = exchange.getIn().getHeader(
                            "correlationId", "unknown", String.class);
                    String sourceType   = exchange.getIn().getHeader(
                            "SourceType", "document", String.class);

                    log.info("Calling LLM: correlationId={} model={}", correlationId, modelName);

                    String llmResponse = llmGateway.chat(systemPrompt, userMessage);

                    // Wrap in SummaryResult record
                    SummaryResult result = new SummaryResult(
                            correlationId,      // sourceId
                            llmResponse,        // summaryText
                            sourceType,         // summaryType  (email | document | thread)
                            Instant.now(),      // generatedAt
                            modelName,          // modelVersion
                            false               // validated — set true by SummaryValidator on success
                    );

                    exchange.getIn().setBody(result);
                    exchange.setProperty("modelVersion", modelName);
                    exchange.setProperty("decisionId",   correlationId);
                    exchange.setProperty("stage",        "summarization");
                })
                .log("LLM response received: sourceId=${body.sourceId} " +
                     "summaryLen=${body.summaryText.length()}")

                // Step 4: Validate — sets validationFailed header on failure
                .process(summaryValidator)

                // Step 5: Route on validation result
                .choice()
                    .when(header("validationFailed").isEqualTo(true))
                        .log(LoggingLevel.WARN,
                             "Summary validation failed [${header.correlationId}]: " +
                             "${header.validationFailureReason}")
                        .to("direct:summaryFallback")
                    .otherwise()
                        .log("Summary validated ✓ — forwarding to output")
                        .to("direct:summaryOutput")
                .end();

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:summaryFallback
        // Handles validation failures and LLM exceptions.
        // Logs and forwards to the dead-letter route for regression capture.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:summaryFallback")
                .routeId("summary-fallback")
                .log(LoggingLevel.WARN,
                     "Summary validation failed for correlationId=${header.correlationId} " +
                     "reason=${header.validationFailureReason} " +
                     "detail=${header.validationFailureDetail}")
                .setProperty("stage", constant("summary-fallback"))
                .to("direct:deadLetter");
    }
}