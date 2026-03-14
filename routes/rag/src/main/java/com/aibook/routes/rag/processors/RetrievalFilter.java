package com.aibook.routes.rag.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Post-retrieval filter that re-ranks, de-duplicates, and caps chunks.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Score each chunk by counting keyword overlaps with the {@code originalQuery} header.</li>
 *   <li>Remove near-duplicate chunks (first 120 chars as fingerprint).</li>
 *   <li>Re-sort by combined Qdrant score × keyword-overlap boost.</li>
 *   <li>Cap at {@code aibook.rag.top-k} chunks.</li>
 * </ol>
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@code List<String>} of retrieved chunks</li>
 *   <li>Header {@code originalQuery} — query text for keyword scoring</li>
 *   <li>Header {@code relevanceScores} — parallel {@code List<Float>} from Qdrant</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Body: filtered and re-ranked {@code List<String>} chunks</li>
 *   <li>Header {@code retrievedCount} updated to final count</li>
 * </ul>
 */
@Component
public class RetrievalFilter implements Processor {

    private static final Logger log = LoggerFactory.getLogger(RetrievalFilter.class);

    /** Fingerprint length for near-duplicate detection. */
    private static final int FINGERPRINT_LEN = 120;

    @Value("${aibook.rag.top-k:5}")
    private int topK;

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        List<String> chunks = exchange.getIn().getBody(List.class);
        if (chunks == null || chunks.isEmpty()) {
            log.debug("RetrievalFilter: no chunks to filter");
            exchange.getIn().setHeader("retrievedCount", 0);
            return;
        }

        String query = exchange.getIn().getHeader("originalQuery", "", String.class);
        List<Float> scores = exchange.getIn().getHeader("relevanceScores", List.class);
        if (scores == null) {
            scores = Collections.nCopies(chunks.size(), 1.0f);
        }

        Set<String> keywords = extractKeywords(query);
        log.debug("RetrievalFilter: filtering {} chunks with {} keywords",
                chunks.size(), keywords.size());

        // Build scored entries
        List<ScoredChunk> scored = new ArrayList<>();
        Set<String> seenFingerprints = new LinkedHashSet<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (chunk == null || chunk.isBlank()) continue;

            // Near-duplicate check via fingerprint
            String fingerprint = chunk.substring(0, Math.min(FINGERPRINT_LEN, chunk.length()))
                    .toLowerCase().replaceAll("\\s+", " ").trim();
            if (!seenFingerprints.add(fingerprint)) {
                log.debug("RetrievalFilter: removing near-duplicate chunk[{}]", i);
                continue;
            }

            float qdrantScore   = (i < scores.size()) ? scores.get(i) : 0.5f;
            double keywordBoost = computeKeywordBoost(chunk, keywords);
            double combined     = qdrantScore * (1.0 + keywordBoost);

            scored.add(new ScoredChunk(chunk, combined));
        }

        // Sort by combined score descending, cap at topK
        List<String> filtered = scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::text)
                .collect(Collectors.toList());

        log.info("RetrievalFilter: {} → {} chunks after filtering (topK={})",
                chunks.size(), filtered.size(), topK);

        exchange.getIn().setBody(filtered);
        exchange.getIn().setHeader("retrievedCount", filtered.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Set<String> extractKeywords(String query) {
        if (query == null || query.isBlank()) return Set.of();
        // Split on whitespace, remove punctuation, lower-case, filter stop words
        Set<String> stopWords = Set.of("a", "an", "the", "is", "in", "on", "at", "to",
                "for", "of", "and", "or", "with", "by", "from", "this", "that", "it", "be");
        return Arrays.stream(query.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2)
                .filter(w -> !stopWords.contains(w))
                .collect(Collectors.toSet());
    }

    /**
     * Returns a value in [0, 1] representing the fraction of query keywords
     * found in the chunk text.
     */
    private double computeKeywordBoost(String chunk, Set<String> keywords) {
        if (keywords.isEmpty()) return 0.0;
        String lowerChunk = chunk.toLowerCase();
        long matches = keywords.stream()
                .filter(lowerChunk::contains)
                .count();
        return (double) matches / keywords.size();
    }

    private record ScoredChunk(String text, double score) {}
}
