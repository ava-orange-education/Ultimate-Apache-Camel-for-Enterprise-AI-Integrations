package com.aibook.routes.rag.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aibook.core.dto.DocumentContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits a {@link DocumentContent} body into overlapping text chunks.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Split the extracted text into sentences (on {@code .}, {@code !}, {@code ?}).</li>
 *   <li>Group sentences into windows of {@code maxTokens} approximate tokens,
 *       with {@code overlapTokens} carried forward from the previous window.</li>
 *   <li>Replace the exchange body with a {@code List<Map<String,Object>>},
 *       where each map contains {@code text}, {@code documentId}, {@code chunkIndex},
 *       and {@code source}.</li>
 *   <li>Set exchange property {@code chunkCount}.</li>
 * </ol>
 *
 * <p>Token approximation: 1 token ≈ 4 characters (GPT-2 rule of thumb).
 */
@Component
public class DocumentChunker implements Processor {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    /** Approximate maximum tokens per chunk. */
    @Value("${aibook.rag.chunk-max-tokens:512}")
    private int maxTokens;

    /** Number of tokens to carry over from the previous chunk for context continuity. */
    @Value("${aibook.rag.chunk-overlap-tokens:50}")
    private int overlapTokens;

    /** Characters per approximate token. */
    private static final int CHARS_PER_TOKEN = 4;

    @Override
    public void process(Exchange exchange) {
        DocumentContent doc = exchange.getIn().getBody(DocumentContent.class);
        if (doc == null) {
            throw new IllegalArgumentException("DocumentChunker: body must be a DocumentContent record");
        }

        String text = doc.extractedText();
        if (text == null || text.isBlank()) {
            log.warn("DocumentChunker: empty extractedText for documentId={}", doc.documentId());
            exchange.getIn().setBody(List.of());
            exchange.setProperty("chunkCount", 0);
            return;
        }

        List<String> sentences = splitIntoSentences(text);
        List<Map<String, Object>> chunks = buildChunks(sentences, doc);

        log.info("DocumentChunker: documentId={} produced {} chunks from {} chars",
                doc.documentId(), chunks.size(), text.length());

        exchange.getIn().setBody(chunks);
        exchange.setProperty("chunkCount", chunks.size());
        exchange.getIn().setHeader("documentId", doc.documentId());
        exchange.getIn().setHeader("source", doc.fileName());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Split text into sentences using common sentence terminators.
     * Preserves the terminator at the end of each sentence.
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        // Split on sentence boundaries: '. ', '! ', '? ', '\n\n'
        String[] rawSentences = text.split("(?<=[.!?])\\s+|(?<=\\n)\\n+");
        for (String s : rawSentences) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        // Fallback: if no sentences detected, treat whole text as one sentence
        if (sentences.isEmpty()) {
            sentences.add(text.trim());
        }
        return sentences;
    }

    /**
     * Group sentences into overlapping chunk windows and build chunk metadata maps.
     */
    private List<Map<String, Object>> buildChunks(List<String> sentences, DocumentContent doc) {
        int maxChars     = maxTokens * CHARS_PER_TOKEN;
        int overlapChars = overlapTokens * CHARS_PER_TOKEN;

        List<Map<String, Object>> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String overlapCarry  = "";
        int chunkIndex       = 0;

        for (String sentence : sentences) {
            // If adding this sentence would exceed max size, flush current chunk
            if (current.length() + sentence.length() > maxChars && current.length() > 0) {
                String chunkText = current.toString().trim();
                chunks.add(buildChunkMap(chunkText, doc, chunkIndex++));

                // Carry overlap from the end of the flushed chunk
                overlapCarry = chunkText.length() > overlapChars
                        ? chunkText.substring(chunkText.length() - overlapChars)
                        : chunkText;

                current = new StringBuilder(overlapCarry);
                if (!overlapCarry.isEmpty() && !overlapCarry.endsWith(" ")) {
                    current.append(" ");
                }
            }
            current.append(sentence).append(" ");
        }

        // Flush remaining text
        if (!current.isEmpty()) {
            String remaining = current.toString().trim();
            if (!remaining.isBlank()) {
                chunks.add(buildChunkMap(remaining, doc, chunkIndex));
            }
        }

        return chunks;
    }

    private Map<String, Object> buildChunkMap(String text, DocumentContent doc, int chunkIndex) {
        return Map.of(
                "text",        text,
                "documentId",  doc.documentId(),
                "chunkIndex",  chunkIndex,
                "source",      doc.fileName() != null ? doc.fileName() : doc.documentId()
        );
    }
}
