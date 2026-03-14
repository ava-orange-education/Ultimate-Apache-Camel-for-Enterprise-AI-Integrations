package com.aibook.routes.rag.processors;

import com.aibook.ai.embeddings.EmbeddingService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Embeds a single document chunk using {@link EmbeddingService}.
 *
 * <p>Reads a chunk map from the exchange body (produced by {@link DocumentChunker})
 * and attaches the computed embedding vector to the exchange property
 * {@code chunkVector}. The original body is preserved for the {@link QdrantUpserter}.
 *
 * <p>Exchange body expected: {@code Map<String, Object>} with keys:
 * {@code text}, {@code documentId}, {@code chunkIndex}, {@code source}.
 */
@Component
public class ChunkEmbedder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbedder.class);

    private final EmbeddingService embeddingService;

    public ChunkEmbedder(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> chunk = exchange.getIn().getBody(Map.class);
        if (chunk == null) {
            throw new IllegalArgumentException("ChunkEmbedder: body must be a chunk Map");
        }

        String text = (String) chunk.get("text");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("ChunkEmbedder: chunk map missing 'text' key");
        }

        String documentId = String.valueOf(chunk.get("documentId"));
        int chunkIndex    = chunk.get("chunkIndex") instanceof Integer i ? i
                          : Integer.parseInt(String.valueOf(chunk.get("chunkIndex")));

        log.debug("ChunkEmbedder: embedding chunk docId={} idx={} textLen={}",
                documentId, chunkIndex, text.length());

        float[] vector = embeddingService.embed(text);

        // Attach vector to exchange property; body stays as the chunk map
        exchange.setProperty("chunkVector", vector);

        log.debug("ChunkEmbedder: embedded chunk docId={} idx={} vectorDim={}",
                documentId, chunkIndex, vector.length);
    }
}
