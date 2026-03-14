package com.aibook.routes.rag.processors;

import com.aibook.core.dto.RagContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Assembles a {@link RagContext} record from the retrieved chunks and query headers.
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@code List<String>} — the filtered/ranked chunks</li>
 *   <li>Header {@code originalQuery} — the user's original query text</li>
 *   <li>Header {@code relevanceScores} — parallel {@code List<Float>} scores</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Body: {@link RagContext} record with numbered chunks and assembled context</li>
 *   <li>Header {@code queryId} — a UUID assigned to this query cycle (for output file naming)</li>
 * </ul>
 *
 * <p>Chunks are numbered in the assembled context:
 * {@code [1] First chunk text\n\n[2] Second chunk text\n\n...}
 */
@Component
public class ContextAssembler implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    @Value("${aibook.rag.max-context-chars:8000}")
    private int maxContextChars;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        List<String> chunks = exchange.getIn().getBody(List.class);
        if (chunks == null) {
            chunks = List.of();
        }

        String originalQuery = exchange.getIn().getHeader("originalQuery", "", String.class);
        List<Float> scores   = exchange.getIn().getHeader("relevanceScores", List.class);
        if (scores == null) {
            scores = List.of();
        }

        // Assign queryId (idempotent: reuse if already set)
        String queryId = exchange.getIn().getHeader("queryId", String.class);
        if (queryId == null || queryId.isBlank()) {
            queryId = UUID.randomUUID().toString();
            exchange.getIn().setHeader("queryId", queryId);
        }

        // Build numbered assembled context, respecting max chars
        StringBuilder assembledBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String numbered = "[" + (i + 1) + "] " + chunks.get(i);
            if (assembledBuilder.length() + numbered.length() + 2 > maxContextChars) {
                log.debug("ContextAssembler: truncating at chunk {} to stay under {} chars",
                        i, maxContextChars);
                break;
            }
            if (!assembledBuilder.isEmpty()) assembledBuilder.append("\n\n");
            assembledBuilder.append(numbered);
        }
        String assembledContext = assembledBuilder.toString();

        RagContext ragContext = new RagContext(
                queryId,
                originalQuery,
                "",              // embeddedQuery — vector not stored as string
                chunks,
                scores,
                assembledContext
        );

        log.info("ContextAssembler: queryId={} assembled {} chunks ({} chars) for query='{}'",
                queryId, chunks.size(), assembledContext.length(),
                originalQuery.length() > 60 ? originalQuery.substring(0, 60) + "..." : originalQuery);

        exchange.getIn().setBody(ragContext);
    }
}
