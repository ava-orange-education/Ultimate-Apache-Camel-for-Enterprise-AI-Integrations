package com.aibook.routes.scoring.processors;

import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.HumanReviewTask;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds a {@link HumanReviewTask} from the current {@link ScoringResult} and
 * any explanation artifact captured during scoring.
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@link ScoringResult} (after {@link ConfidenceEvaluator})</li>
 *   <li>Header {@code routingDecision} — determines task priority</li>
 *   <li>Exchange property {@code explanationArtifact} — optional {@link ExplanationArtifact}</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Body: {@link HumanReviewTask} ready for dispatch to the review queue</li>
 *   <li>Header {@code reviewTaskId} — the generated task UUID</li>
 *   <li>Header {@code reviewQueue}  — the target queue URI from configuration</li>
 * </ul>
 *
 * <p>Priority mapping:
 * <pre>
 *   ESCALATE   → URGENT
 *   REVIEW     → MEDIUM  (or HIGH if score > 0.6)
 *   (default)  → LOW
 * </pre>
 */
@Component
public class HumanReviewTaskBuilder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(HumanReviewTaskBuilder.class);

    private final String reviewQueue;
    private final String defaultPriority;

    public HumanReviewTaskBuilder(AiPipelineProperties properties) {
        this.reviewQueue     = properties.humanReview().queue();
        this.defaultPriority = "MEDIUM";
        log.info("HumanReviewTaskBuilder: queue={}", reviewQueue);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        ScoringResult result;

        if (body instanceof ScoringResult sr) {
            result = sr;
        } else if (body instanceof Map<?, ?> rawMap) {
            // Tolerate Map bodies from graph-enrichment and other callers that don't
            // produce a ScoringResult. Synthesize a minimal ScoringResult from the map.
            Map<String, Object> m = (Map<String, Object>) rawMap;
            String requestId       = String.valueOf(m.getOrDefault("requestId", "unknown"));
            String entityId        = String.valueOf(m.getOrDefault("entityId",  "unknown"));
            double score           = parseDouble(m.getOrDefault("score",      0.5));
            double confidence      = parseDouble(m.getOrDefault("confidence", 0.5));
            String routingDecision = String.valueOf(m.getOrDefault("routingDecision",
                    exchange.getIn().getHeader("routingDecision", "REVIEW", String.class)));
            @SuppressWarnings("unchecked")
            Map<String, Object> features = m.get("features") instanceof Map<?, ?> fm
                    ? new HashMap<>((Map<String, Object>) fm)
                    : Map.of();
            result = new ScoringResult(requestId, entityId, score, confidence,
                    "unknown", routingDecision, features, null);
            log.info("HumanReviewTaskBuilder: synthesised ScoringResult from Map for requestId={}", requestId);
        } else {
            throw new IllegalArgumentException(
                    "HumanReviewTaskBuilder: body must be a ScoringResult record or Map, got: "
                    + (body == null ? "null" : body.getClass().getSimpleName()));
        }

        String routingDecision = exchange.getIn()
                .getHeader("routingDecision", "REVIEW", String.class);

        // ── Priority mapping ──────────────────────────────────────────────────
        String priority = switch (routingDecision) {
            case "ESCALATE" -> "URGENT";
            case "REVIEW"   -> result.score() > 0.6 ? "HIGH" : "MEDIUM";
            default         -> defaultPriority;
        };

        // ── Optional explanation artifact ─────────────────────────────────────
        ExplanationArtifact explanation = exchange.getProperty(
                "explanationArtifact", ExplanationArtifact.class);

        // Build a lightweight explanation if none was captured upstream
        if (explanation == null) {
            explanation = new ExplanationArtifact(
                    result.requestId(),
                    Map.copyOf(result.featuresUsed()),
                    Map.of("score", result.score(), "confidence", result.confidence()),
                    "Routed to human review: decision=" + routingDecision,
                    result.modelVersion(),
                    null
            );
        }

        // ── Build the task ────────────────────────────────────────────────────
        HumanReviewTask task = new HumanReviewTask(
                null,                  // taskId auto-generated
                result.requestId(),    // decisionId
                explanation,
                result,
                reviewQueue,
                priority,
                null                   // createdAt auto-generated
        );

        exchange.getIn().setBody(task);
        exchange.getIn().setHeader("reviewTaskId", task.taskId());
        exchange.getIn().setHeader("reviewQueue",  reviewQueue);
        exchange.getIn().setHeader("priority",     priority);

        log.info("HumanReviewTaskBuilder: created taskId={} for requestId={} priority={} queue={}",
                task.taskId(), result.requestId(), priority, reviewQueue);
    }

    private double parseDouble(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return 0.5; }
    }
}
