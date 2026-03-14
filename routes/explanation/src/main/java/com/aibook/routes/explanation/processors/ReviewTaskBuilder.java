package com.aibook.routes.explanation.processors;

import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.HumanReviewTask;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Builds a {@link HumanReviewTask} from the current exchange state and sets it as
 * the exchange body, ready for dispatch to the human review queue.
 *
 * <p>This is the <em>explanation-module</em> version of the task builder —
 * it creates a richer task that includes the full {@link ExplanationArtifact}
 * and a review-briefing narrative loaded from
 * {@code prompts/explanation/human-review-brief.txt}.
 *
 * <h3>Priority mapping</h3>
 * <pre>
 *   score &gt;= 0.75  → URGENT
 *   score &gt;= 0.60  → HIGH
 *   score &gt;= 0.40  → MEDIUM
 *   otherwise      → LOW
 * </pre>
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@link ExplanationArtifact} <em>or</em> {@link ScoringResult}</li>
 *   <li>Header {@code ScoringResult} — fallback if body is not a ScoringResult</li>
 *   <li>Exchange property {@code explanationArtifact}</li>
 *   <li>Exchange property {@code decisionId}</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Body: {@link HumanReviewTask}</li>
 *   <li>Header {@code reviewTaskId}</li>
 *   <li>Header {@code priority}</li>
 * </ul>
 */
@Component
public class ReviewTaskBuilder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskBuilder.class);

    private final String reviewQueue;
    private final String defaultPriority;

    public ReviewTaskBuilder(AiPipelineProperties properties) {
        this.reviewQueue     = properties.humanReview().queue();
        this.defaultPriority = "MEDIUM";
        log.info("ReviewTaskBuilder: queue={}", reviewQueue);
    }

    @Override
    public void process(Exchange exchange) {
        // ── Resolve ScoringResult ─────────────────────────────────────────────
        ScoringResult scoring = null;
        Object body = exchange.getIn().getBody();
        if (body instanceof ScoringResult sr) {
            scoring = sr;
        } else if (body instanceof HumanReviewTask hrt) {
            // Already a task (forwarded from scoring module) — just propagate headers
            scoring = hrt.scoringResult();
        } else {
            scoring = exchange.getIn().getHeader("ScoringResult", ScoringResult.class);
        }

        // ── Resolve ExplanationArtifact ───────────────────────────────────────
        ExplanationArtifact artifact = exchange.getProperty(
                "explanationArtifact", ExplanationArtifact.class);
        if (artifact == null && body instanceof ExplanationArtifact ea) {
            artifact = ea;
        }
        if (artifact == null) {
            // Build a minimal artifact from available exchange state
            String decisionId = exchange.getProperty("decisionId",
                    scoring != null ? scoring.requestId() : "unknown", String.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> evidence = exchange.getProperty("evidence",
                    scoring != null ? scoring.featuresUsed() : Map.of(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> signals  = exchange.getProperty("signals", Map.of(), Map.class);
            String rationale = scoring != null
                    ? "Routing decision: " + scoring.routingDecision()
                    + " | Score: " + scoring.score()
                    + " | Confidence: " + scoring.confidence()
                    : "Human review required";
            artifact = new ExplanationArtifact(decisionId, evidence, signals,
                    rationale, scoring != null ? scoring.modelVersion() : "unknown", null);
        }

        // ── Determine priority ────────────────────────────────────────────────
        double score = scoring != null ? scoring.score() : 0.5;
        String priority;
        if (score >= 0.75)      priority = "URGENT";
        else if (score >= 0.60) priority = "HIGH";
        else if (score >= 0.40) priority = "MEDIUM";
        else                    priority = "LOW";

        // If routing decision is ESCALATE, always at least HIGH
        if (scoring != null && "ESCALATE".equals(scoring.routingDecision())
                && !"URGENT".equals(priority)) {
            priority = "HIGH";
        }

        // ── Resolve decisionId ────────────────────────────────────────────────
        String decisionId = artifact.decisionId();
        if (decisionId == null || decisionId.isBlank() || "unknown".equals(decisionId)) {
            decisionId = exchange.getProperty("decisionId",
                    scoring != null ? scoring.requestId() : "unknown", String.class);
        }

        // ── Build task ────────────────────────────────────────────────────────
        HumanReviewTask task = new HumanReviewTask(
                null,          // taskId auto-generated
                decisionId,
                artifact,
                scoring,
                reviewQueue,
                priority,
                null           // createdAt auto-generated
        );

        exchange.getIn().setBody(task);
        exchange.getIn().setHeader("reviewTaskId", task.taskId());
        exchange.getIn().setHeader("priority",     task.priority());

        log.info("ReviewTaskBuilder: created taskId={} decisionId={} priority={} queue={}",
                task.taskId(), decisionId, priority, reviewQueue);
    }
}
