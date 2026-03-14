package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.HumanReviewTask;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reintegrates human reviewer feedback into the pipeline by:
 * <ol>
 *   <li>Building a structured feedback document suitable for file persistence.</li>
 *   <li>Formatting a golden regression case in the canonical format expected by
 *       {@code datasets/golden/regression_cases/}.</li>
 *   <li>Setting exchange headers for downstream file routing.</li>
 * </ol>
 *
 * <h3>Feedback document shape</h3>
 * <pre>
 * {
 *   "taskId":         "...",
 *   "decisionId":     "...",
 *   "reviewOutcome":  "APPROVED|REJECTED",
 *   "reviewNotes":    "...",
 *   "reviewerId":     "...",
 *   "score":          0.55,
 *   "confidence":     0.65,
 *   "routingDecision":"REVIEW",
 *   "rationale":      "LLM narrative...",
 *   "feedbackAt":     "ISO-8601",
 *   "regressionCase": { ... }
 * }
 * </pre>
 *
 * <p>Reads body: {@link HumanReviewTask}.<br>
 * Reads headers: {@code reviewOutcome}, {@code reviewNotes}, {@code reviewerId}.<br>
 * Writes body: {@code Map<String, Object>} feedback document.<br>
 * Writes header {@code feedbackTaskId} for file-name construction.
 */
@Component
public class FeedbackReintegrator implements Processor {

    private static final Logger log = LoggerFactory.getLogger(FeedbackReintegrator.class);

    @Value("${aibook.feedback.output-dir:${java.io.tmpdir}/aibook/feedback}")
    private String feedbackOutputDir;

    @Override
    public void process(Exchange exchange) {
        HumanReviewTask task = exchange.getIn().getBody(HumanReviewTask.class);

        String outcome    = exchange.getIn().getHeader("reviewOutcome", "UNKNOWN", String.class);
        String notes      = exchange.getIn().getHeader("reviewNotes",   "",        String.class);
        String reviewerId = exchange.getIn().getHeader("reviewerId",    "unknown", String.class);

        // ── Extract context from task ─────────────────────────────────────────
        String taskId     = task != null ? task.taskId()    : "unknown";
        String decisionId = task != null ? task.decisionId() : "unknown";

        ScoringResult scoring = task != null ? task.scoringResult() : null;
        double score      = scoring != null ? scoring.score()      : 0.0;
        double confidence = scoring != null ? scoring.confidence() : 0.0;
        String routingDec = scoring != null ? scoring.routingDecision() : "UNKNOWN";

        ExplanationArtifact artifact = task != null ? task.explanation() : null;
        String rationale = artifact != null ? artifact.rationale() : "";

        // ── Build feedback document ───────────────────────────────────────────
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("taskId",          taskId);
        feedback.put("decisionId",      decisionId);
        feedback.put("reviewOutcome",   outcome);
        feedback.put("reviewNotes",     notes);
        feedback.put("reviewerId",      reviewerId);
        feedback.put("score",           score);
        feedback.put("confidence",      confidence);
        feedback.put("routingDecision", routingDec);
        feedback.put("rationale",       rationale);
        feedback.put("feedbackAt",      Instant.now().toString());

        // ── Build regression case (golden dataset format) ─────────────────────
        Map<String, Object> regression = new LinkedHashMap<>();
        regression.put("caseId",               "regression-" + taskId);
        regression.put("decisionId",            decisionId);
        regression.put("humanVerdict",          outcome);
        regression.put("expectedDecision",      routingDec);
        regression.put("score",                 score);
        regression.put("confidence",            confidence);
        regression.put("featuresSnapshot",      scoring != null ? scoring.featuresUsed() : Map.of());
        regression.put("rationale",             rationale);
        regression.put("reviewerNotes",         notes);
        regression.put("capturedAt",            Instant.now().toString());

        feedback.put("regressionCase", regression);

        exchange.getIn().setBody(feedback);
        exchange.getIn().setHeader("feedbackTaskId", taskId);

        log.info("FeedbackReintegrator: taskId={} decisionId={} outcome={} reviewer={}",
                taskId, decisionId, outcome, reviewerId);
    }
}
