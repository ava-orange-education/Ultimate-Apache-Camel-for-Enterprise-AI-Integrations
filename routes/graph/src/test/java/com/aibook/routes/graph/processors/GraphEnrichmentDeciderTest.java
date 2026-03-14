package com.aibook.routes.graph.processors;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphEnrichmentDecider}.
 *
 * <p>Verifies threshold logic for graphEnrichmentNeeded without any Spring context.
 */
class GraphEnrichmentDeciderTest {

    private DefaultCamelContext    camelContext;
    private GraphEnrichmentDecider decider;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        decider = new GraphEnrichmentDecider();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(Double confidence, Double score, Integer riskFlagCount) {
        Exchange ex = new DefaultExchange(camelContext);
        if (confidence    != null) ex.getIn().setHeader("confidence",    confidence);
        if (score         != null) ex.getIn().setHeader("score",         score);
        if (riskFlagCount != null) ex.getIn().setHeader("riskFlagCount", riskFlagCount);
        ex.getIn().setHeader("entityId", "test-entity");
        return ex;
    }

    // ── Medium confidence triggers enrichment ─────────────────────────────────

    @Test
    @DisplayName("Confidence in medium band (0.5–0.8) → graphEnrichmentNeeded=true")
    void mediumConfidence_triggerEnrichment() throws Exception {
        Exchange ex = exchange(0.65, 0.2, 0);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Confidence exactly 0.5 (lower bound inclusive) → needed=true")
    void confidenceExact05_triggerEnrichment() throws Exception {
        Exchange ex = exchange(0.50, 0.2, 0);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Confidence exactly 0.8 (upper bound exclusive) → needed=false (high confidence)")
    void confidenceExact08_noEnrichment() throws Exception {
        // 0.8 is NOT in [0.5, 0.8) — use a clean score so no other trigger fires
        Exchange ex = exchange(0.80, 0.1, 0);
        decider.process(ex);
        // score=0.1 is < 0.3 so no scoreMidRange, riskFlags=0, confidence=0.8 not in range
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("Confidence > 0.8 (high) → needed=false (unless other triggers)")
    void highConfidence_noEnrichment() throws Exception {
        Exchange ex = exchange(0.92, 0.1, 0);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isFalse();
    }

    // ── Mid-range score triggers enrichment ───────────────────────────────────

    @Test
    @DisplayName("Score in mid range [0.3–0.65] → needed=true (borderline case)")
    void midRangeScore_triggerEnrichment() throws Exception {
        Exchange ex = exchange(0.9, 0.45, 0);    // high confidence but mid score
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Score below mid range → needed=false (when confidence also high)")
    void lowScore_noEnrichment() throws Exception {
        Exchange ex = exchange(0.9, 0.1, 0);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isFalse();
    }

    // ── Risk flags trigger enrichment ─────────────────────────────────────────

    @Test
    @DisplayName("riskFlagCount > 0 → needed=true regardless of confidence/score")
    void riskFlags_triggerEnrichment() throws Exception {
        Exchange ex = exchange(0.95, 0.05, 3);   // high confidence, low score, but risk flags
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isTrue();
    }

    // ── Caller override ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Header graphEnrichmentNeeded=true (caller override) → kept true")
    void callerForced_staysTrue() throws Exception {
        Exchange ex = exchange(0.95, 0.05, 0);   // would be false otherwise
        ex.getIn().setHeader("graphEnrichmentNeeded", true);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isTrue();
    }

    // ── Missing headers ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing confidence/score headers → needed=false (safe default)")
    void missingHeaders_safeDefault() throws Exception {
        Exchange ex = exchange(null, null, null);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class)).isFalse();
    }

    // ── Parameterised boundary sweep ──────────────────────────────────────────

    @ParameterizedTest(name = "confidence={0} score={1} riskFlags={2} → needed={3}")
    @CsvSource({
            "0.65, 0.40, 0, true",    // medium confidence + mid score
            "0.65, 0.10, 0, true",    // medium confidence alone
            "0.90, 0.45, 0, true",    // high confidence but mid score
            "0.90, 0.10, 0, false",   // high confidence, low score, no flags
            "0.90, 0.10, 1, true",    // risk flag present
            "0.50, 0.30, 0, true",    // lower bounds both hit
            "0.49, 0.29, 0, false",   // just below both lower bounds
            "0.80, 0.66, 0, false",   // just above both upper bounds
    })
    void parametrisedBoundarySweep(double confidence, double score, int flags, boolean expected)
            throws Exception {
        Exchange ex = exchange(confidence, score, flags);
        decider.process(ex);
        assertThat(ex.getIn().getHeader("graphEnrichmentNeeded", Boolean.class))
                .isEqualTo(expected);
    }
}
