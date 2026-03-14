package com.aibook.routes.rag.processors;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetrievalFilter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Near-duplicate chunks are removed</li>
 *   <li>Chunks are re-ranked by keyword overlap with the query</li>
 *   <li>Results are capped at topK</li>
 *   <li>Empty input is handled gracefully</li>
 * </ul>
 */
class RetrievalFilterTest {

    private DefaultCamelContext camelContext;
    private RetrievalFilter     filter;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        filter = new RetrievalFilter();
        setField(filter, "topK", 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(List<String> chunks, String query) {
        return exchange(chunks, query, null);
    }

    private Exchange exchange(List<String> chunks, String query, List<Float> scores) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(new ArrayList<>(chunks));
        ex.getIn().setHeader("originalQuery", query);
        if (scores != null) {
            ex.getIn().setHeader("relevanceScores", scores);
        }
        return ex;
    }

    // ── Deduplication ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Near-duplicate chunks (same first 120 chars) are removed")
    void nearDuplicates_removed() throws Exception {
        // base is 130 chars so both base and dup share the same first 120 chars
        String base  = "Apache Camel is an open-source integration framework based on EIPs that provides routing, mediation, and transformation capabilities.";
        String dup   = base + " Extra trailing content that makes this chunk different after 120 chars.";
        String other = "LangChain4j provides Java LLM integration abstractions.";

        Exchange ex = exchange(List.of(base, dup, other), "Camel integration framework");
        filter.process(ex);

        @SuppressWarnings("unchecked")
        List<String> result = ex.getIn().getBody(List.class);
        // Only one of the duplicates should remain
        long camelCount = result.stream()
                .filter(c -> c.startsWith("Apache Camel is an open-source"))
                .count();
        assertThat(camelCount).isEqualTo(1);
        assertThat(result).anyMatch(c -> c.contains("LangChain4j"));
    }

    // ── TopK capping ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Results are capped at topK (5)")
    void results_cappedAtTopK() throws Exception {
        setField(filter, "topK", 3);
        List<String> chunks = List.of(
                "Chunk one about Camel routing.",
                "Chunk two about embeddings.",
                "Chunk three about Qdrant search.",
                "Chunk four about Spring Boot.",
                "Chunk five about RAG pipelines."
        );

        Exchange ex = exchange(chunks, "Camel routing");
        filter.process(ex);

        @SuppressWarnings("unchecked")
        List<String> result = ex.getIn().getBody(List.class);
        assertThat(result).hasSizeLessThanOrEqualTo(3);
    }

    // ── Keyword re-ranking ────────────────────────────────────────────────────

    @Test
    @DisplayName("Chunks with keyword overlap are ranked higher")
    void keywordOverlap_ranksChunksHigher() throws Exception {
        // All scores equal, but first chunk has no keyword match
        List<String> chunks = List.of(
                "This chunk talks about something unrelated entirely.",
                "This chunk discusses Apache Camel routing with Exchange processors."
        );
        List<Float> scores = List.of(0.8f, 0.8f);

        Exchange ex = exchange(chunks, "Apache Camel routing processors", scores);
        filter.process(ex);

        @SuppressWarnings("unchecked")
        List<String> result = ex.getIn().getBody(List.class);
        // The Camel chunk should appear first (higher keyword boost)
        assertThat(result.get(0)).contains("Apache Camel routing");
    }

    // ── Empty input ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty chunk list returns empty result with retrievedCount=0")
    void emptyInput_returnsEmpty() throws Exception {
        Exchange ex = exchange(List.of(), "some query");
        filter.process(ex);

        @SuppressWarnings("unchecked")
        List<String> result = ex.getIn().getBody(List.class);
        // Body is left unchanged (empty list) when no chunks to filter
        assertThat(result).isEmpty();
        assertThat(ex.getIn().getHeader("retrievedCount", Integer.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("retrievedCount header updated after filtering")
    void retrievedCount_updatedAfterFilter() throws Exception {
        List<String> chunks = List.of("Chunk A.", "Chunk B.", "Chunk C.");
        Exchange ex = exchange(chunks, "query");
        filter.process(ex);

        assertThat(ex.getIn().getHeader("retrievedCount", Integer.class))
                .isGreaterThanOrEqualTo(1)
                .isLessThanOrEqualTo(3);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
