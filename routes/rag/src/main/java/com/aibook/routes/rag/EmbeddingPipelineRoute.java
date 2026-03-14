package com.aibook.routes.rag;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.rag.processors.QueryEmbedder;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Embedding pipeline route — entry point for query-time RAG.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:embedQuery
 *     → queryEmbedder         (embeds body String, sets headers queryVector + originalQuery)
 *     → direct:retrieveKnowledge
 * </pre>
 *
 * <p>The REST query endpoint is declared in {@link RagAssemblyRoute} so all query
 * REST configuration lives in one place.
 */
@Component
public class EmbeddingPipelineRoute extends RouteBuilder {

    private final QueryEmbedder  queryEmbedder;
    private final AiErrorHandler aiErrorHandler;

    public EmbeddingPipelineRoute(QueryEmbedder queryEmbedder,
                                  AiErrorHandler aiErrorHandler) {
        this.queryEmbedder   = queryEmbedder;
        this.aiErrorHandler  = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalArgumentException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "EmbeddingPipelineRoute: invalid input [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "EmbeddingPipelineRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:embedQuery
        // Input:  plain-text query String in exchange body
        // Output: headers queryVector (float[]) + originalQuery (String)
        //         → forwarded to direct:retrieveKnowledge
        // ═════════════════════════════════════════════════════════════════════
        from("direct:embedQuery")
                .routeId("rag-embed-query")
                .log(LoggingLevel.INFO,
                     "EmbeddingPipelineRoute: embedding query [exchangeId=${exchangeId}]")

                // Embed the query string; sets headers queryVector and originalQuery
                .process(queryEmbedder)
                .log(LoggingLevel.DEBUG,
                     "EmbeddingPipelineRoute: query embedded, forwarding to retrieval")

                .to("direct:retrieveKnowledge");
    }
}