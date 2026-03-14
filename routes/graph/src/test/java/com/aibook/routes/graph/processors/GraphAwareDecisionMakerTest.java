package com.aibook.routes.graph.processors;

import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphAwareDecisionMaker}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Risk elevation when connectedEntityCount &gt; 5 AND avgStrength &gt; 0.7</li>
 *   <li>Risk elevation when clusterDensity &gt; 0.8</li>
 *   <li>No elevation when below both thresholds</li>
 *   <li>ScoringResult body is updated with ESCALATE routing decision</li>
 *   <li>graphRiskReason header is populated</li>
 * </ul>
 */
class GraphAwareDecisionMakerTest {

    private DefaultCamelContext      camelContext;
    private GraphAwareDecisionMaker  decisionMaker;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        decisionMaker = new GraphAwareDecisionMaker();
        // Inject default thresholds via reflection
        setField(decisionMaker, "riskClusterThreshold", 5);
        setField(decisionMaker, "strengthThreshold",    0.7);
        setField(decisionMaker, "densityThreshold",     0.8);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(int connectedCount, double avgStrength, double clusterDensity) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setHeader("connectedEntityCount",    connectedCount);
        ex.getIn().setHeader("avgRelationshipStrength", avgStrength);
        ex.getIn().setHeader("clusterDensity",          clusterDensity);
        ex.getIn().setHeader("maxPathDepth",            3);
        ex.getIn().setHeader("entityId",                "test-entity");
        return ex;
    }

    private Exchange exchangeWithResult(int connectedCount, double avgStrength,
                                        double clusterDensity, double score, double confidence) {
        Exchange ex = exchange(connectedCount, avgStrength, clusterDensity);
        ex.getIn().setHeader("score",      score);
        ex.getIn().setHeader("confidence", confidence);
        ScoringResult result = new ScoringResult(
                "req-1", "test-entity", score, confidence,
                "test-model", "REVIEW", Map.of("kyc", "VERIFIED"), null);
        ex.getIn().setBody(result);
        return ex;
    }

    // ── Cluster risk: connectedCount > 5 AND avgStrength > 0.7 ───────────────

    @Test
    @DisplayName("connectedEntityCount > 5 AND avgStrength > 0.7 → graphRiskElevated=true")
    void clusterRisk_elevated() throws Exception {
        Exchange ex = exchange(6, 0.75, 0.3);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("connectedEntityCount exactly 5 (threshold exclusive) → not elevated by cluster")
    void connectedCountExact5_notElevatedByCluster() throws Exception {
        Exchange ex = exchange(5, 0.80, 0.3);   // clusterDensity also below threshold
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("avgStrength exactly 0.7 (threshold exclusive) → not elevated by cluster")
    void avgStrengthExact07_notElevatedByCluster() throws Exception {
        Exchange ex = exchange(8, 0.70, 0.3);   // count > 5 but strength not > 0.7
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("High count but low strength → not elevated by cluster rule")
    void highCountLowStrength_notElevated() throws Exception {
        Exchange ex = exchange(10, 0.40, 0.2);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    // ── Density risk: clusterDensity > 0.8 ───────────────────────────────────

    @Test
    @DisplayName("clusterDensity > 0.8 → graphRiskElevated=true (density rule)")
    void highDensity_elevated() throws Exception {
        Exchange ex = exchange(2, 0.3, 0.85);   // low count and strength, but high density
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("clusterDensity exactly 0.8 (exclusive threshold) → not elevated by density")
    void densityExact08_notElevated() throws Exception {
        Exchange ex = exchange(2, 0.3, 0.80);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    // ── No risk ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Low count, low strength, low density → not elevated")
    void lowRisk_notElevated() throws Exception {
        Exchange ex = exchange(2, 0.3, 0.1);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    // ── routingDecision header ────────────────────────────────────────────────

    @Test
    @DisplayName("Risk elevated → routingDecision header set to ESCALATE")
    void riskElevated_routingDecisionEscalate() throws Exception {
        Exchange ex = exchange(8, 0.85, 0.5);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("No risk → routingDecision header not set by this processor")
    void noRisk_routingDecisionNotSet() throws Exception {
        Exchange ex = exchange(2, 0.3, 0.1);
        decisionMaker.process(ex);
        // Processor does not set routingDecision when no risk elevation
        assertThat(ex.getIn().getHeader("routingDecision")).isNull();
    }

    // ── ScoringResult body update ─────────────────────────────────────────────

    @Test
    @DisplayName("ScoringResult body is updated with ESCALATE when risk is elevated")
    void scoringResultBody_updatedWithEscalate() throws Exception {
        Exchange ex = exchangeWithResult(8, 0.85, 0.5, 0.55, 0.65);
        decisionMaker.process(ex);

        ScoringResult result = ex.getIn().getBody(ScoringResult.class);
        assertThat(result).isNotNull();
        assertThat(result.routingDecision()).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("ScoringResult body preserves original routingDecision when no risk")
    void scoringResultBody_preservedWhenNoRisk() throws Exception {
        Exchange ex = exchangeWithResult(2, 0.3, 0.1, 0.3, 0.85);
        decisionMaker.process(ex);

        ScoringResult result = ex.getIn().getBody(ScoringResult.class);
        assertThat(result).isNotNull();
        assertThat(result.routingDecision()).isEqualTo("REVIEW");  // original preserved
    }

    @Test
    @DisplayName("ScoringResult.featuresUsed enriched with graph signals")
    void scoringResultFeatures_enrichedWithGraphSignals() throws Exception {
        Exchange ex = exchangeWithResult(8, 0.85, 0.5, 0.55, 0.65);
        decisionMaker.process(ex);

        ScoringResult result = ex.getIn().getBody(ScoringResult.class);
        assertThat(result.featuresUsed())
                .containsKey("graph_risk_elevated")
                .containsKey("graph_connected_entity_count")
                .containsKey("graph_avg_relationship_strength")
                .containsKey("graph_cluster_density");
    }

    // ── graphRiskReason header ────────────────────────────────────────────────

    @Test
    @DisplayName("graphRiskReason header is set and non-blank")
    void graphRiskReason_set() throws Exception {
        Exchange ex = exchange(8, 0.85, 0.5);
        decisionMaker.process(ex);

        String reason = ex.getIn().getHeader("graphRiskReason", String.class);
        assertThat(reason).isNotBlank();
        assertThat(reason).contains("cluster risk");
    }

    @Test
    @DisplayName("No risk → graphRiskReason explains why (no risk)")
    void graphRiskReason_noRiskExplained() throws Exception {
        Exchange ex = exchange(2, 0.3, 0.1);
        decisionMaker.process(ex);

        String reason = ex.getIn().getHeader("graphRiskReason", String.class);
        assertThat(reason).isNotBlank();
        assertThat(reason).contains("no graph risk");
    }

    // ── Parameterised threshold sweep ─────────────────────────────────────────

    @ParameterizedTest(name = "count={0} strength={1} density={2} → elevated={3}")
    @CsvSource({
            "6,  0.75, 0.3,  true",   // cluster rule
            "10, 0.90, 0.9,  true",   // both rules
            "3,  0.90, 0.85, true",   // density rule
            "5,  0.80, 0.3,  false",  // count not > 5
            "6,  0.70, 0.3,  false",  // strength not > 0.7
            "2,  0.30, 0.80, false",  // density not > 0.8
            "0,  0.0,  0.0,  false",  // zeroes
    })
    void parametrisedThresholds(int count, double strength, double density, boolean expected)
            throws Exception {
        Exchange ex = exchange(count, strength, density);
        decisionMaker.process(ex);
        assertThat(ex.getIn().getHeader("graphRiskElevated", Boolean.class)).isEqualTo(expected);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
