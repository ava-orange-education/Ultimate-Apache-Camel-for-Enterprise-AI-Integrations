package com.aibook.routes.rag;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.rag.processors.QdrantRetriever;
import com.aibook.routes.rag.processors.RetrievalFilter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Query retrieval route — performs vector search and post-processes results.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:retrieveKnowledge
 *     → qdrantRetriever   (calls VectorSearchService using header queryVector)
 *     → retrievalFilter   (re-ranks by keyword overlap, de-duplicates, caps at topK)
 *     → direct:assembleRagContext
 * </pre>
 *
 * <h3>Headers consumed</h3>
 * <ul>
 *   <li>{@code queryVector}    — {@code float[]} from {@link EmbeddingPipelineRoute}</li>
 *   <li>{@code originalQuery}  — String from {@link EmbeddingPipelineRoute}</li>
 *   <li>{@code qdrantCollection} (optional) — override the default Qdrant collection</li>
 * </ul>
 *
 * <h3>Headers produced</h3>
 * <ul>
 *   <li>{@code retrievedCount} — number of chunks after filtering</li>
 *   <li>{@code relevanceScores} — parallel {@code List<Float>} of Qdrant similarity scores</li>
 * </ul>
 */
@Component
public class QueryRetrievalRoute extends RouteBuilder {

    private final QdrantRetriever  qdrantRetriever;
    private final RetrievalFilter  retrievalFilter;
    private final AiErrorHandler   aiErrorHandler;

    public QueryRetrievalRoute(QdrantRetriever qdrantRetriever,
                               RetrievalFilter retrievalFilter,
                               AiErrorHandler aiErrorHandler) {
        this.qdrantRetriever = qdrantRetriever;
        this.retrievalFilter = retrievalFilter;
        this.aiErrorHandler  = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalStateException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "QueryRetrievalRoute: missing prerequisite header [${routeId}]: "
                     + "${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "QueryRetrievalRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:retrieveKnowledge
        // Input:  header queryVector (float[]) + header originalQuery (String)
        // Output: body List<String> chunks → direct:assembleRagContext
        // ═════════════════════════════════════════════════════════════════════
        from("direct:retrieveKnowledge")
                .routeId("rag-query-retrieval")
                .log(LoggingLevel.INFO,
                     "QueryRetrievalRoute: starting vector search for query="
                     + "'${header.originalQuery}'")

                // Vector similarity search using pre-computed query embedding
                .process(qdrantRetriever)
                .log(LoggingLevel.INFO,
                     "QueryRetrievalRoute: retrieved ${header.retrievedCount} chunks from Qdrant")

                // Post-retrieval: re-rank, de-duplicate, cap at topK
                .process(retrievalFilter)
                .log(LoggingLevel.INFO,
                     "QueryRetrievalRoute: ${header.retrievedCount} chunks after filtering")

                .to("direct:assembleRagContext");
    }
}