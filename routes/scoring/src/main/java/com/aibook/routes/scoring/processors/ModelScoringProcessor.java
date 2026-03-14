package com.aibook.routes.scoring.processors;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.ai.llm.StructuredOutputParser;
import com.aibook.core.dto.ScoringRequest;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Calls the LLM scoring model (via {@link LlmGateway}) with the assembled feature set
 * and parses the structured JSON response into a {@link ScoringResult} DTO.
 *
 * <h3>LLM interaction</h3>
 * <ol>
 *   <li>Serialises the feature map to a string and fills {@code prompts/scoring/score-explanation.txt}
 *       via {@link LlmGateway#generateFromTemplate}.</li>
 *   <li>Parses the JSON response {@code {score, confidence, reasoning}} via
 *       {@link StructuredOutputParser#parseToMap}.</li>
 *   <li>Constructs an immutable {@link ScoringResult} and sets it as the exchange body.</li>
 * </ol>
 *
 * <h3>Fallback</h3>
 * If the LLM call fails, a rule-based score is computed from the feature map
 * so the pipeline can continue rather than stalling the entire request.
 *
 * <h3>Headers set</h3>
 * <ul>
 *   <li>{@code score}        — double risk score [0.0–1.0]</li>
 *   <li>{@code confidence}   — double model confidence [0.0–1.0]</li>
 *   <li>{@code modelVersion} — name of the model used</li>
 * </ul>
 */
@Component
public class ModelScoringProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ModelScoringProcessor.class);

    private static final String SCORE_PROMPT = "prompts/scoring/score-explanation.txt";

    @Value("${aibook.llm.model-name:llama3.2}")
    private String modelName;

    private final LlmGateway            llmGateway;
    private final StructuredOutputParser outputParser;

    public ModelScoringProcessor(LlmGateway llmGateway, StructuredOutputParser outputParser) {
        this.llmGateway   = llmGateway;
        this.outputParser = outputParser;
    }

    @Override
    public void process(Exchange exchange) {
        ScoringRequest req = exchange.getIn().getBody(ScoringRequest.class);
        if (req == null) {
            throw new IllegalArgumentException(
                    "ModelScoringProcessor: body must be a ScoringRequest record");
        }

        String featuresStr = req.features().toString();
        double score, confidence;
        String reasoning;

        try {
            String llmResponse = llmGateway.generateFromTemplate(
                    SCORE_PROMPT,
                    Map.of(
                            "entityId",   req.entityId(),
                            "entityType", exchange.getIn().getHeader("entityType", "Entity", String.class),
                            "features",   featuresStr
                    )
            );

            Map<String, Object> parsed = outputParser.parseToMap(llmResponse);
            score      = parseDouble(parsed.getOrDefault("score",      0.5));
            confidence = parseDouble(parsed.getOrDefault("confidence", score));
            reasoning  = parsed.getOrDefault("reasoning", parsed.getOrDefault("explanation", "")).toString();

            log.info("ModelScoringProcessor: LLM scored requestId={} score={} confidence={}",
                    req.requestId(), score, confidence);

        } catch (Exception e) {
            // Fallback: rule-based scoring from feature map
            log.warn("ModelScoringProcessor: LLM call failed ({}), using rule-based fallback",
                    e.getMessage());
            score      = computeRuleBasedScore(req.features());
            confidence = computeRuleBasedConfidence(req.features(), score);
            reasoning  = "rule-based fallback: LLM unavailable";
        }

        // Clamp to [0.0, 1.0]
        score      = Math.max(0.0, Math.min(1.0, score));
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        // Build the immutable result (routingDecision filled by ConfidenceEvaluator downstream)
        ScoringResult result = new ScoringResult(
                req.requestId(),
                req.entityId(),
                score,
                confidence,
                modelName,
                "PENDING",       // routingDecision set by ConfidenceEvaluator
                req.features(),
                null             // scoredAt auto-generated
        );

        exchange.getIn().setBody(result);
        exchange.getIn().setHeader("score",        score);
        exchange.getIn().setHeader("confidence",   confidence);
        exchange.getIn().setHeader("modelVersion", modelName);
        exchange.getIn().setHeader("reasoning",    reasoning);

        // Propagate for audit
        exchange.setProperty("decisionId",   req.requestId());
        exchange.setProperty("stage",        "model-scoring");
        exchange.setProperty("modelVersion", modelName);
        exchange.setProperty("signals",      Map.of("score", score, "confidence", confidence));

        log.info("ModelScoringProcessor: requestId={} entityId={} score={} confidence={}",
                req.requestId(), req.entityId(), score, confidence);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double parseDouble(Object value) {
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /**
     * Simple rule-based scoring used as fallback when the LLM is unavailable.
     * Weights a few standard features into a risk score.
     */
    private double computeRuleBasedScore(Map<String, Object> features) {
        double score = 0.3;   // baseline

        // Lower score (less risk) for verified KYC
        if ("VERIFIED".equalsIgnoreCase(String.valueOf(features.get("kyc_status")))) {
            score -= 0.1;
        }
        // Increase score for failed transactions
        Object failedTx = features.get("failed_transactions_30d");
        if (failedTx != null) {
            double failed = parseDouble(failedTx);
            score += Math.min(0.3, failed * 0.05);
        }
        // Increase score for open support tickets
        Object tickets = features.get("support_tickets_open");
        if (tickets != null) {
            score += Math.min(0.2, parseDouble(tickets) * 0.05);
        }
        // Increase score for high-risk time window
        if (Boolean.TRUE.equals(features.get("high_risk_time_window"))) {
            score += 0.05;
        }
        // Incorporate prior score if available
        Object priorScore = features.get("prior_score");
        if (priorScore != null) {
            score = score * 0.7 + parseDouble(priorScore) * 0.3;
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * Rule-based confidence estimation used as fallback when the LLM is unavailable.
     *
     * <p>Produces higher confidence for unambiguous low-risk cases (VERIFIED KYC,
     * zero failed transactions, zero tickets) so that the ConfidenceEvaluator can
     * correctly route them to APPROVE rather than ESCALATE.
     */
    private double computeRuleBasedConfidence(Map<String, Object> features, double score) {
        boolean verified = "VERIFIED".equalsIgnoreCase(
                String.valueOf(features.get("kyc_status")));
        double failedTx = parseDouble(features.getOrDefault("failed_transactions_30d", 0));
        double tickets  = parseDouble(features.getOrDefault("support_tickets_open",  0));
        int    riskFlags = (int) parseDouble(features.getOrDefault("risk_flag_count", 0));

        // Clear low-risk profile → high confidence
        if (verified && failedTx == 0 && tickets == 0 && riskFlags == 0 && score < 0.4) {
            return 0.85;
        }
        // Clear high-risk profile → moderately high confidence
        if (score >= 0.65 && (failedTx >= 5 || riskFlags >= 3)) {
            return 0.65;
        }
        // Ambiguous middle ground → medium confidence
        return 0.5;
    }
}
