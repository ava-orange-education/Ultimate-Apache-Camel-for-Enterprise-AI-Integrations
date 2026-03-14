package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.HumanReviewTask;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles an explicit human review decision submitted via the REST API
 * ({@code POST /api/review/decision/{taskId}}).
 *
 * <p>Reads from the exchange:
 * <ul>
 *   <li>Body: {@code Map<String, Object>} with keys {@code outcome}, {@code notes}</li>
 *   <li>Header {@code taskId} — from the path parameter</li>
 *   <li>Exchange property {@code reviewTask} — previously queued {@link HumanReviewTask}</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Header {@code reviewOutcome} — validated "APPROVED" or "REJECTED"</li>
 *   <li>Header {@code reviewNotes}</li>
 *   <li>Header {@code reviewerId} — from body or default "human"</li>
 *   <li>Body: the original {@link HumanReviewTask} (preserved for FeedbackReintegrator)</li>
 * </ul>
 */
@Component
public class ReviewDecisionProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ReviewDecisionProcessor.class);

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        // Attempt to read the decision body
        Object bodyObj = exchange.getIn().getBody();
        Map<String, Object> decision;
        if (bodyObj instanceof Map<?, ?> m) {
            decision = (Map<String, Object>) m;
        } else {
            decision = Map.of();
            log.warn("ReviewDecisionProcessor: body is not a Map, using empty decision");
        }

        // ── Validate outcome ──────────────────────────────────────────────────
        // Accept either "outcome" or "decision" as the key (demo sends "decision")
        String outcome = null;
        if (decision.containsKey("outcome")) {
            outcome = String.valueOf(decision.get("outcome")).toUpperCase().strip();
        } else if (decision.containsKey("decision")) {
            outcome = String.valueOf(decision.get("decision")).toUpperCase().strip();
        } else {
            outcome = "";
        }
        if (!"APPROVED".equals(outcome) && !"REJECTED".equals(outcome)) {
            log.warn("ReviewDecisionProcessor: invalid outcome '{}' — defaulting to REJECTED", outcome);
            outcome = "REJECTED";
        }

        // Accept "notes" or "reviewerNotes" as the notes key
        String notes = decision.containsKey("notes")
                ? String.valueOf(decision.get("notes"))
                : String.valueOf(decision.getOrDefault("reviewerNotes", ""));
        // Accept "reviewerId" or "reviewedBy" as the reviewer key
        String reviewerId = decision.containsKey("reviewerId")
                ? String.valueOf(decision.get("reviewerId"))
                : String.valueOf(decision.getOrDefault("reviewedBy", "human"));

        // ── Retrieve queued task (if stored on exchange) ──────────────────────
        HumanReviewTask task = exchange.getProperty("reviewTask", HumanReviewTask.class);

        // ── Build a minimal placeholder task if none was stored ───────────────
        if (task == null) {
            String taskId     = exchange.getIn().getHeader("taskId", "unknown", String.class);
            String decisionId = exchange.getIn().getHeader("decisionId", taskId, String.class);
            ScoringResult sr  = exchange.getIn().getHeader("ScoringResult", ScoringResult.class);
            task = new HumanReviewTask(taskId, decisionId, null, sr,
                    "direct:human-review", "MEDIUM", Instant.now());
            log.warn("ReviewDecisionProcessor: no stored task for taskId={} — created placeholder", taskId);
        }

        // ── Set response headers ──────────────────────────────────────────────
        exchange.getIn().setHeader("reviewOutcome", outcome);
        exchange.getIn().setHeader("reviewNotes",   notes);
        exchange.getIn().setHeader("reviewerId",    reviewerId);

        // Restore task as body for FeedbackReintegrator
        exchange.getIn().setBody(task);

        log.info("ReviewDecisionProcessor: taskId={} outcome={} reviewer={}",
                task.taskId(), outcome, reviewerId);
    }
}
