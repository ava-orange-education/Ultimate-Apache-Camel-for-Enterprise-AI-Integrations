package com.aibook.routes.rag.processors;

import com.aibook.ai.vector.QdrantVectorStore;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Upserts an embedded chunk into Qdrant.
 *
 * <p>Reads the chunk map from the exchange body (produced by {@link DocumentChunker})
 * and the embedding vector from exchange property {@code chunkVector}
 * (set by {@link ChunkEmbedder}).
 *
 * <p>Builds a payload with {@code text}, {@code documentId}, {@code chunkIndex},
 * and {@code source} before calling {@link QdrantVectorStore#upsert}.
 *
 * <p>The Qdrant collection name is resolved in this precedence:
 * <ol>
 *   <li>Exchange header {@code qdrantCollection}</li>
 *   <li>Property {@code aibook.rag.collection} from application.yml</li>
 * </ol>
 */
@Component
public class QdrantUpserter implements Processor {

    private static final Logger log = LoggerFactory.getLogger(QdrantUpserter.class);

    private final QdrantVectorStore vectorStore;

    @Value("${aibook.qdrant.collection:knowledge-base}")
    private String defaultCollection;

    public QdrantUpserter(QdrantVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> chunk = exchange.getIn().getBody(Map.class);
        if (chunk == null) {
            throw new IllegalArgumentException("QdrantUpserter: body must be a chunk Map");
        }

        float[] vector = exchange.getProperty("chunkVector", float[].class);
        if (vector == null) {
            throw new IllegalStateException(
                    "QdrantUpserter: exchange property 'chunkVector' is missing — " +
                    "ensure ChunkEmbedder runs before QdrantUpserter");
        }

        // Determine collection: header overrides default
        String collection = exchange.getIn()
                .getHeader("qdrantCollection", defaultCollection, String.class);

        // Build a stable chunk ID: docId + chunkIndex
        String documentId  = String.valueOf(chunk.get("documentId"));
        int    chunkIndex  = chunk.get("chunkIndex") instanceof Integer i ? i
                           : Integer.parseInt(String.valueOf(chunk.get("chunkIndex")));
        String chunkId     = documentId + "_chunk_" + chunkIndex;

        // Build payload for retrieval
        Map<String, Object> payload = new HashMap<>();
        payload.put("text",        chunk.get("text"));
        payload.put("documentId",  documentId);
        payload.put("chunkIndex",  chunkIndex);
        payload.put("source",      chunk.getOrDefault("source", documentId));

        log.debug("QdrantUpserter: upserting chunkId={} into collection={}",
                chunkId, collection);

        vectorStore.upsert(collection, chunkId, vector, payload);

        log.info("QdrantUpserter: upserted chunkId={} collection={} vectorDim={}",
                chunkId, collection, vector.length);
    }
}
