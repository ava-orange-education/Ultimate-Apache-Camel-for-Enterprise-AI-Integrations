package com.aibook.routes.rag.processors;

import com.aibook.ai.embeddings.EmbeddingService;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Embeds the user query string using {@link EmbeddingService}.
 *
 * <p>Reads the exchange body as a plain-text query string, calls
 * {@link EmbeddingService#embed(String)}, and sets:
 * <ul>
 *   <li>Header {@code queryVector}   — the raw {@code float[]} embedding</li>
 *   <li>Header {@code originalQuery} — the original query string (for later use in prompts)</li>
 * </ul>
 *
 * <p>The exchange body is left unchanged (still the query string) so that
 * downstream processors can read it if needed.
 */
@Component
public class QueryEmbedder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbedder.class);

    private final EmbeddingService embeddingService;

    public QueryEmbedder(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Override
    public void process(Exchange exchange) {
        String query = exchange.getIn().getBody(String.class);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "QueryEmbedder: exchange body must be a non-blank query string");
        }

        log.debug("QueryEmbedder: embedding query '{}' ({} chars)", query, query.length());

        float[] vector = embeddingService.embed(query);

        exchange.getIn().setHeader("queryVector",   vector);
        exchange.getIn().setHeader("originalQuery", query);

        log.debug("QueryEmbedder: embedded query vectorDim={}", vector.length);
    }
}
