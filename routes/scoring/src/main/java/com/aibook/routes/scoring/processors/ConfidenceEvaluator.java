package com.aibook.routes.scoring.processors;

import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evaluates the model score and confidence against configured thresholds and sets
 * the {@code routingDecision} header that drives the downstream choice() block.
 *
 * <h3>Decision bands</h3>
 * <pre>
 *   confidence > 0.8  AND score < highRiskThreshold  → APPROVE
 *   confidence 0.5–0.8 OR  score in mid-range        → REVIEW
 *   confidence < 0.5  OR  score >= highRiskThreshold  → ESCALATE
 * </pre>
 *
 * <h3>Feature safety-net</h3>
 * A deterministic feature score is computed from the raw {@code featuresUsed} map
 * using the same additive rules as the scoring prompt.  If the LLM score would route
 * a clearly high-risk entity to APPROVE (feature score >= 0.75, LLM score < 0.40)
 * or a clearly low-risk entity to ESCALATE (feature score < 0.40, LLM score >= 0.75),
 * the LLM score is clamped to the feature-based floor/ceiling so the routing decision
 * is always consistent with the observed features.
 */
@Component
public class ConfidenceEvaluator implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceEvaluator.class);

    /** Scores at or above this value are considered high-risk → ESCALATE. */
    static final double HIGH_RISK_SCORE_THRESHOLD = 0.75;

    /** Scores below this value are considered low-risk → APPROVE (if confidence is high). */
    static final double LOW_RISK_SCORE_CEILING = 0.40;

    private final double confidenceThreshold;

    public ConfidenceEvaluator(AiPipelineProperties properties) {
        this.confidenceThreshold = properties.scoring().confidenceThreshold();
        log.info("ConfidenceEvaluator: confidenceThreshold={}", confidenceThreshold);
    }

    @Override
    public void process(Exchange exchange) {
        ScoringResult result = exchange.getIn().getBody(ScoringResult.class);
        if (result == null) {
            throw new IllegalArgumentException(
                    "ConfidenceEvaluator: body must be a ScoringResult record");
        }

        double llmScore   = result.score();
        double confidence = result.confidence();

        // ── Feature-based safety-net ──────────────────────────────────────────
        double featureScore = computeFeatureScore(result.featuresUsed());
        double score        = applyFeatureSafetyNet(llmScore, featureScore, result.requestId());

        // ── Determine confidence band ─────────────────────────────────────────
        String confidenceBand;
        if (confidence > 0.8) {
            confidenceBand = "HIGH";
        } else if (confidence >= 0.5) {
            confidenceBand = "MEDIUM";
        } else {
            confidenceBand = "LOW";
        }

        // ── Determine routing decision ────────────────────────────────────────
        String routingDecision;
        if (score >= HIGH_RISK_SCORE_THRESHOLD || confidence < 0.5) {
            routingDecision = "ESCALATE";
        } else if (confidence > 0.8 && score < HIGH_RISK_SCORE_THRESHOLD) {
            routingDecision = "APPROVE";
        } else {
            routingDecision = "REVIEW";
        }

        log.info("ConfidenceEvaluator: requestId={} score={} confidence={} band={} decision={}",
                result.requestId(), score, confidence, confidenceBand, routingDecision);

        ScoringResult updated = new ScoringResult(
                result.requestId(),
                result.entityId(),
                score,
                confidence,
                result.modelVersion(),
                routingDecision,
                result.featuresUsed(),
                result.scoredAt()
        );

        exchange.getIn().setBody(updated);
        exchange.getIn().setHeader("routingDecision", routingDecision);
        exchange.getIn().setHeader("confidenceBand",  confidenceBand);
        exchange.getIn().setHeader("score",           score);
        exchange.getIn().setHeader("confidence",      confidence);
    }

    /**
     * Computes a deterministic additive feature score using the same rules as the
     * scoring prompt, so the safety-net and the LLM are calibrated identically.
     *
     * <p>Rule table (mirrors score-explanation.txt):
     * <pre>
     *   kyc_status UNVERIFIED           +0.40
     *   kyc_status PENDING              +0.20
     *   risk_flag_count >= 6            +0.35
     *   risk_flag_count 3–5             +0.15
     *   failed_transactions_30d >= 10   +0.15
     *   failed_transactions_30d 5–9     +0.08
     *   countries_transacted >= 10      +0.10
     *   countries_transacted 5–9        +0.05
     *   account_age_days <= 7           +0.10
     *   account_age_days 8–90           +0.05
     * </pre>
     */
    double computeFeatureScore(Map<String, Object> features) {
        if (features == null || features.isEmpty()) return 0.0;

        double s = 0.0;

        // 1. KYC status
        String kyc = String.valueOf(features.getOrDefault("kyc_status", ""));
        if ("UNVERIFIED".equalsIgnoreCase(kyc))  s += 0.40;
        else if ("PENDING".equalsIgnoreCase(kyc)) s += 0.20;

        // 2. Risk flag count
        int flags = toInt(features.get("risk_flag_count"));
        if (flags >= 6)      s += 0.35;
        else if (flags >= 3) s += 0.15;

        // 3. Failed transactions in last 30 days
        int failed = toInt(features.get("failed_transactions_30d"));
        if (failed >= 10)     s += 0.15;
        else if (failed >= 5) s += 0.08;

        // 4. Countries transacted
        int countries = toInt(features.get("countries_transacted"));
        if (countries >= 10)     s += 0.10;
        else if (countries >= 5) s += 0.05;

        // 5. Account age
        int ageDays = toInt(features.get("account_age_days"));
        if (ageDays <= 7)        s += 0.10;
        else if (ageDays <= 90)  s += 0.05;

        return Math.min(s, 1.0);
    }

    /**
     * Clamps the LLM score when it contradicts the feature evidence:
     * <ul>
     *   <li>If features clearly indicate HIGH risk (featureScore >= 0.75) but the LLM
     *       returned a LOW score (< LOW_RISK_SCORE_CEILING), clamp up to featureScore.</li>
     *   <li>If features clearly indicate LOW risk (featureScore < LOW_RISK_SCORE_CEILING) but
     *       the LLM returned a HIGH score (>= HIGH_RISK_SCORE_THRESHOLD), clamp down to
     *       featureScore.</li>
     *   <li>Otherwise trust the LLM score.</li>
     * </ul>
     */
    double applyFeatureSafetyNet(double llmScore, double featureScore, String requestId) {
        boolean featureClearlyHigh = featureScore >= HIGH_RISK_SCORE_THRESHOLD;
        boolean featureClearlyLow  = featureScore < LOW_RISK_SCORE_CEILING;
        boolean llmSaysLow         = llmScore < LOW_RISK_SCORE_CEILING;
        boolean llmSaysHigh        = llmScore >= HIGH_RISK_SCORE_THRESHOLD;

        if (featureClearlyHigh && llmSaysLow) {
            log.warn("ConfidenceEvaluator: safety-net CLAMP-UP requestId={} " +
                     "llmScore={} featureScore={} → using featureScore",
                     requestId, llmScore, featureScore);
            return featureScore;
        }
        if (featureClearlyLow && llmSaysHigh) {
            log.warn("ConfidenceEvaluator: safety-net CLAMP-DOWN requestId={} " +
                     "llmScore={} featureScore={} → using featureScore",
                     requestId, llmScore, featureScore);
            return featureScore;
        }
        return llmScore;
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }
}