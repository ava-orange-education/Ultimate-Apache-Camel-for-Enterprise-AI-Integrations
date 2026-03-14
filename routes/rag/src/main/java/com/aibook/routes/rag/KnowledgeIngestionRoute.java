package com.aibook.routes.rag;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.rag.processors.ChunkEmbedder;
import com.aibook.routes.rag.processors.DocumentChunker;
import com.aibook.routes.rag.processors.QdrantUpserter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

/**
 * Knowledge ingestion route — chunks, embeds, and upserts documents into Qdrant.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   REST POST /api/rag/ingest
 *     → direct:ingestKnowledge
 *       → documentChunker   (splits DocumentContent into overlapping text chunks)
 *       → split(body())     (fan-out: one exchange per chunk)
 *           → chunkEmbedder  (calls EmbeddingService.embed(), attaches vector)
 *           → qdrantUpserter (calls QdrantVectorStore.upsert() with full payload)
 *       → log("Ingested N chunks for doc X")
 * </pre>
 *
 * <h3>Headers set by this route</h3>
 * <ul>
 *   <li>{@code documentId} — from the DocumentContent DTO</li>
 * </ul>
 *
 * <h3>Exchange properties</h3>
 * <ul>
 *   <li>{@code chunkCount}  — total chunks produced (set by {@link DocumentChunker})</li>
 *   <li>{@code chunkVector} — current chunk's embedding (set by {@link ChunkEmbedder})</li>
 * </ul>
 */
@Component
public class KnowledgeIngestionRoute extends RouteBuilder {

    private final DocumentChunker documentChunker;
    private final ChunkEmbedder   chunkEmbedder;
    private final QdrantUpserter  qdrantUpserter;
    private final AiErrorHandler  aiErrorHandler;

    public KnowledgeIngestionRoute(DocumentChunker documentChunker,
                                   ChunkEmbedder chunkEmbedder,
                                   QdrantUpserter qdrantUpserter,
                                   AiErrorHandler aiErrorHandler) {
        this.documentChunker = documentChunker;
        this.chunkEmbedder   = chunkEmbedder;
        this.qdrantUpserter  = qdrantUpserter;
        this.aiErrorHandler  = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalArgumentException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "KnowledgeIngestionRoute: invalid input [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "KnowledgeIngestionRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST entry point ──────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/rag")
                .description("RAG knowledge ingestion and query endpoints")
                .post("/ingest")
                    .description("Ingest a DocumentContent JSON body into the vector store")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:ingestKnowledge");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:ingestKnowledge
        // Input:  DocumentContent JSON body
        // Output: N upserted Qdrant points (one per chunk)
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ingestKnowledge")
                .routeId("rag-knowledge-ingestion")
                .log(LoggingLevel.INFO,
                     "KnowledgeIngestionRoute: received document [exchangeId=${exchangeId}]")

                // Parse JSON body into DocumentContent DTO
                .unmarshal().json(com.aibook.core.dto.DocumentContent.class)

                // Chunk the document text into overlapping windows
                .process(documentChunker)
                .log(LoggingLevel.INFO,
                     "KnowledgeIngestionRoute: ${exchangeProperty.chunkCount} chunks for "
                     + "documentId=${header.documentId}")

                // Fan-out — embed and upsert each chunk independently
                .split(body())
                    .parallelProcessing(false)
                    .log(LoggingLevel.DEBUG,
                         "KnowledgeIngestionRoute: embedding chunk ${body[chunkIndex]} "
                         + "for doc ${body[documentId]}")
                    .process(chunkEmbedder)
                    .process(qdrantUpserter)
                .end()

                // Log final summary
                .log(LoggingLevel.INFO,
                     "KnowledgeIngestionRoute: ingested ${exchangeProperty.chunkCount} chunks "
                     + "for documentId=${header.documentId}")

                // Return lightweight acknowledgement
                .setBody(simple(
                        "{\"status\":\"ingested\","
                        + "\"documentId\":\"${header.documentId}\","
                        + "\"chunks\":${exchangeProperty.chunkCount}}"));
    }
}