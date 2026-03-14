package com.aibook.routes.scoring.processors;

import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link ConfidenceEvaluator}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Routing decision at threshold boundaries (APPROVE / REVIEW / ESCALATE)</li>
 *   <li>Feature-score computation ({@link ConfidenceEvaluator#computeFeatureScore})</li>
 *   <li>Safety-net clamping ({@link ConfidenceEvaluator#applyFeatureSafetyNet})</li>
 *   <li>End-to-end: high-risk / low-risk demo payloads routed correctly</li>
 * </ul>
 */
class ConfidenceEvaluatorTest {

    private DefaultCamelContext camelContext;
    private ConfidenceEvaluator evaluator;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();

        AiPipelineProperties.ScoringConfig scoringCfg =
                new AiPipelineProperties.ScoringConfig(0.6, "default", false, "mock", "");
        AiPipelineProperties props = new AiPipelineProperties(
                null, null, null, null, scoringCfg, null);

        evaluator = new ConfidenceEvaluator(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    /** Build an exchange with the given score/confidence and empty features. */
    private Exchange exchange(double score, double confidence) {
        return exchange(score, confidence, Map.of());
    }

    /** Build an exchange with explicit features map. */
    private Exchange exchange(double score, double confidence, Map<String, Object> features) {
        ScoringResult result = new ScoringResult(
                "req-001", "entity-1", score, confidence,
                "test-model", "PENDING", features, null);
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(result);
        return ex;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Routing decision — threshold boundary tests (no features → no safety-net)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("confidence > 0.8 and score < 0.75 → APPROVE")
    void highConfidenceLowScore_approve() throws Exception {
        Exchange ex = exchange(0.3, 0.85);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("APPROVE");
        assertThat(ex.getIn().getHeader("confidenceBand")).isEqualTo("HIGH");
        assertThat(ex.getIn().getBody(ScoringResult.class).routingDecision()).isEqualTo("APPROVE");
    }

    @Test
    @DisplayName("confidence exactly 0.8 falls in MEDIUM band → REVIEW")
    void confidenceExactly08_review() throws Exception {
        Exchange ex = exchange(0.3, 0.80);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("confidenceBand")).isEqualTo("MEDIUM");
        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("confidence 0.5–0.8 → REVIEW")
    void mediumConfidence_review() throws Exception {
        Exchange ex = exchange(0.4, 0.65);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("REVIEW");
        assertThat(ex.getIn().getHeader("confidenceBand")).isEqualTo("MEDIUM");
    }

    @Test
    @DisplayName("confidence exactly 0.5 → REVIEW (boundary inclusive)")
    void confidenceExactly05_review() throws Exception {
        Exchange ex = exchange(0.4, 0.50);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("confidence < 0.5 → ESCALATE")
    void lowConfidence_escalate() throws Exception {
        Exchange ex = exchange(0.3, 0.3);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
        assertThat(ex.getIn().getHeader("confidenceBand")).isEqualTo("LOW");
    }

    /** High-risk features that justify a score >= 0.75 (UNVERIFIED + 8 flags + 12 failed). */
    private static final Map<String, Object> HIGH_RISK_FEATURES = Map.of(
            "kyc_status", "UNVERIFIED",
            "risk_flag_count", 8,
            "failed_transactions_30d", 12,
            "countries_transacted", 15,
            "account_age_days", 1
    );

    @Test
    @DisplayName("score >= 0.75 with matching high-risk features → ESCALATE")
    void highRiskScore_escalate() throws Exception {
        Exchange ex = exchange(0.80, 0.90, HIGH_RISK_FEATURES);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("score exactly 0.75 with high-risk features → ESCALATE (threshold inclusive)")
    void scoreExactly075_escalate() throws Exception {
        Exchange ex = exchange(0.75, 0.85, HIGH_RISK_FEATURES);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
    }

    @ParameterizedTest(name = "score={0} confidence={1} → {2}")
    @CsvSource({
            // Low scores with no features → safety-net leaves score unchanged → APPROVE / REVIEW
            "0.10, 0.90, APPROVE",
            "0.50, 0.90, APPROVE",
            "0.74, 0.90, APPROVE",
            "0.40, 0.65, REVIEW",
            "0.40, 0.50, REVIEW",
            "0.40, 0.49, ESCALATE",
            "0.40, 0.00, ESCALATE"
    })
    void parametrisedDecisionBoundaries(double score, double confidence, String expected)
            throws Exception {
        // No features → feature score = 0.0 (low). Safety-net only clamps high llmScore down,
        // but these scores are all < 0.75, so no clamping occurs.
        Exchange ex = exchange(score, confidence);
        evaluator.process(ex);
        assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo(expected);
    }

    @Test
    @DisplayName("score and confidence headers are set from ScoringResult")
    void scoreAndConfidenceHeaders_set() throws Exception {
        Exchange ex = exchange(0.35, 0.88);
        evaluator.process(ex);

        assertThat(ex.getIn().getHeader("score",      Double.class)).isEqualTo(0.35);
        assertThat(ex.getIn().getHeader("confidence", Double.class)).isEqualTo(0.88);
    }

    @Test
    @DisplayName("ScoringResult body is updated with routingDecision field")
    void scoringResultBody_updatedWithDecision() throws Exception {
        Exchange ex = exchange(0.3, 0.85);
        evaluator.process(ex);

        ScoringResult result = ex.getIn().getBody(ScoringResult.class);
        assertThat(result).isNotNull();
        assertThat(result.routingDecision()).isEqualTo("APPROVE");
    }

    @Test
    @DisplayName("Null body throws IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> evaluator.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ConfidenceEvaluator");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // computeFeatureScore — additive rule tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("computeFeatureScore")
    class ComputeFeatureScoreTests {

        @Test
        @DisplayName("all low-risk features → score near 0")
        void allLowRisk_nearZero() {
            Map<String, Object> features = Map.of(
                    "kyc_status", "VERIFIED",
                    "risk_flag_count", 0,
                    "failed_transactions_30d", 2,
                    "countries_transacted", 3,
                    "account_age_days", 730
            );
            double score = evaluator.computeFeatureScore(features);
            assertThat(score).isEqualTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("UNVERIFIED + 8 flags + 12 failed + 15 countries + age 1 day → max risk ≥ 0.75")
        void allHighRisk_exceedsEscalateThreshold() {
            // score-003 / explain-demo-001 profile
            Map<String, Object> features = Map.of(
                    "kyc_status", "UNVERIFIED",
                    "risk_flag_count", 8,
                    "failed_transactions_30d", 12,
                    "countries_transacted", 15,
                    "account_age_days", 1
            );
            double score = evaluator.computeFeatureScore(features);
            // 0.40 + 0.35 + 0.15 + 0.10 + 0.10 = 1.10 → capped at 1.0
            assertThat(score).isEqualTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("PENDING + 3 flags + 5 failed + 7 countries + age 30 days → REVIEW band")
        void mediumRisk_reviewBand() {
            // score-002 profile
            Map<String, Object> features = Map.of(
                    "kyc_status", "PENDING",
                    "risk_flag_count", 3,
                    "failed_transactions_30d", 5,
                    "countries_transacted", 7,
                    "account_age_days", 30
            );
            double score = evaluator.computeFeatureScore(features);
            // 0.20 + 0.15 + 0.08 + 0.05 + 0.05 = 0.53
            assertThat(score).isEqualTo(0.53, within(0.001));
        }

        @Test
        @DisplayName("null features map → 0.0")
        void nullFeatures_returnsZero() {
            assertThat(evaluator.computeFeatureScore(null)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("empty features map → 0.0")
        void emptyFeatures_returnsZero() {
            assertThat(evaluator.computeFeatureScore(Map.of())).isEqualTo(0.0);
        }

        @Test
        @DisplayName("numeric values as strings are parsed correctly")
        void stringNumericValues_parsed() {
            Map<String, Object> features = Map.of(
                    "risk_flag_count", "7",
                    "failed_transactions_30d", "11",
                    "countries_transacted", "12",
                    "account_age_days", "3",
                    "kyc_status", "UNVERIFIED"
            );
            double score = evaluator.computeFeatureScore(features);
            // 0.40 + 0.35 + 0.15 + 0.10 + 0.10 = 1.10 → capped at 1.0
            assertThat(score).isEqualTo(1.0, within(0.001));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // applyFeatureSafetyNet — clamping logic tests
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("applyFeatureSafetyNet")
    class SafetyNetTests {

        @Test
        @DisplayName("featureScore HIGH + llm LOW → clamped UP to featureScore")
        void featureHigh_llmLow_clampsUp() {
            double result = evaluator.applyFeatureSafetyNet(0.20, 0.90, "req-test");
            assertThat(result).isEqualTo(0.90, within(0.001));
        }

        @Test
        @DisplayName("featureScore LOW + llm HIGH → clamped DOWN to featureScore")
        void featureLow_llmHigh_clampsDown() {
            double result = evaluator.applyFeatureSafetyNet(0.85, 0.10, "req-test");
            assertThat(result).isEqualTo(0.10, within(0.001));
        }

        @Test
        @DisplayName("both in REVIEW band → LLM score trusted")
        void bothInReviewBand_trustedLlm() {
            double result = evaluator.applyFeatureSafetyNet(0.55, 0.50, "req-test");
            assertThat(result).isEqualTo(0.55, within(0.001));
        }

        @Test
        @DisplayName("featureScore HIGH + llm also HIGH → LLM score trusted")
        void featureHighLlmHigh_trusted() {
            double result = evaluator.applyFeatureSafetyNet(0.80, 0.85, "req-test");
            assertThat(result).isEqualTo(0.80, within(0.001));
        }

        @Test
        @DisplayName("featureScore LOW + llm also LOW → LLM score trusted")
        void featureLowLlmLow_trusted() {
            double result = evaluator.applyFeatureSafetyNet(0.15, 0.05, "req-test");
            assertThat(result).isEqualTo(0.15, within(0.001));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // End-to-end: demo payloads routed correctly despite LLM drift
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Demo payload routing")
    class DemoPayloadTests {

        @Test
        @DisplayName("score-001: KYC=VERIFIED, 0 flags, 2 failed → APPROVE even if LLM drifts")
        void score001_approved() throws Exception {
            Map<String, Object> features = Map.of(
                    "kyc_status", "VERIFIED",
                    "risk_flag_count", 0,
                    "failed_transactions_30d", 2,
                    "countries_transacted", 3,
                    "account_age_days", 730
            );
            // Simulate LLM drifting high — safety-net should clamp it DOWN
            Exchange ex = exchange(0.80, 0.90, features);
            evaluator.process(ex);
            // featureScore = 0.0 < 0.40, llmScore = 0.80 >= 0.75 → clamp down to 0.0 → APPROVE
            assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("APPROVE");
        }

        @Test
        @DisplayName("score-002: KYC=PENDING, 3 flags, 5 failed → REVIEW")
        void score002_review() throws Exception {
            Map<String, Object> features = Map.of(
                    "kyc_status", "PENDING",
                    "risk_flag_count", 3,
                    "failed_transactions_30d", 5,
                    "countries_transacted", 7,
                    "account_age_days", 15
            );
            // Simulate LLM scoring low — featureScore=0.53, llmScore=0.25
            // featureScore not >= 0.75, so no clamp; llmScore=0.25 with confidence 0.85 → APPROVE
            // But with confidence 0.65 (medium) → REVIEW
            Exchange ex = exchange(0.25, 0.65, features);
            evaluator.process(ex);
            assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("REVIEW");
        }

        @Test
        @DisplayName("score-003: KYC=UNVERIFIED, 8 flags, 12 failed → ESCALATE even if LLM drifts low")
        void score003_escalated() throws Exception {
            Map<String, Object> features = Map.of(
                    "kyc_status", "UNVERIFIED",
                    "risk_flag_count", 8,
                    "failed_transactions_30d", 12,
                    "countries_transacted", 15,
                    "account_age_days", 1
            );
            // Simulate LLM drifting low — safety-net clamps up to 1.0 → ESCALATE
            Exchange ex = exchange(0.25, 0.85, features);
            evaluator.process(ex);
            assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
        }

        @Test
        @DisplayName("explain-demo-001: KYC=UNVERIFIED, 7 flags, 10 failed → ESCALATE")
        void explainDemo001_escalated() throws Exception {
            Map<String, Object> features = Map.of(
                    "kyc_status", "UNVERIFIED",
                    "risk_flag_count", 7,
                    "failed_transactions_30d", 10,
                    "countries_transacted", 12,
                    "account_age_days", 2
            );
            Exchange ex = exchange(0.20, 0.85, features);
            evaluator.process(ex);
            assertThat(ex.getIn().getHeader("routingDecision")).isEqualTo("ESCALATE");
        }
    }
}
