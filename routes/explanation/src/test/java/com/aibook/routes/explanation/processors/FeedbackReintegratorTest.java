package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.HumanReviewTask;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FeedbackReintegrator}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Feedback document contains all required fields</li>
 *   <li>Regression case nested inside feedback document</li>
 *   <li>feedbackTaskId header set for file naming</li>
 *   <li>Graceful handling when reviewOutcome is missing</li>
 *   <li>ScoringResult fields surfaced in regression case</li>
 * </ul>
 */
class FeedbackReintegratorTest {

    private DefaultCamelContext  camelContext;
    private FeedbackReintegrator reintegrator;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        reintegrator = new FeedbackReintegrator();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private ScoringResult scoringResult(String requestId, double score, double confidence) {
        return new ScoringResult(requestId, "entity-1", score, confidence,
                "test-model", "REVIEW", Map.of("kyc", "VERIFIED"), null);
    }

    private ExplanationArtifact artifact(String decisionId) {
        return new ExplanationArtifact(decisionId,
                Map.of("score", 0.5), Map.of("routingDecision", "REVIEW"),
                "Test rationale.", "test-model", null);
    }

    private HumanReviewTask task(String taskId, ScoringResult sr, ExplanationArtifact a) {
        return new HumanReviewTask(taskId, sr.requestId(), a, sr,
                "direct:human-review", "MEDIUM", null);
    }

    private Exchange exchange(HumanReviewTask task, String outcome, String notes) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(task);
        ex.getIn().setHeader("reviewOutcome", outcome);
        ex.getIn().setHeader("reviewNotes",   notes);
        ex.getIn().setHeader("reviewerId",    "test-reviewer");
        return ex;
    }

    // ── Feedback document ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Feedback document contains all required fields")
    void feedbackDocument_containsRequiredFields() throws Exception {
        ScoringResult sr  = scoringResult("req-1", 0.55, 0.65);
        HumanReviewTask t = task("task-001", sr, artifact("req-1"));
        Exchange ex       = exchange(t, "APPROVED", "Looks good");

        reintegrator.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> feedback = (Map<String, Object>) ex.getIn().getBody();
        assertThat(feedback)
                .containsKey("taskId")
                .containsKey("decisionId")
                .containsKey("reviewOutcome")
                .containsKey("reviewNotes")
                .containsKey("reviewerId")
                .containsKey("score")
                .containsKey("confidence")
                .containsKey("routingDecision")
                .containsKey("rationale")
                .containsKey("feedbackAt")
                .containsKey("regressionCase");
    }

    @Test
    @DisplayName("reviewOutcome APPROVED preserved in feedback document")
    void reviewOutcome_preserved() throws Exception {
        ScoringResult sr  = scoringResult("req-2", 0.3, 0.85);
        HumanReviewTask t = task("task-002", sr, artifact("req-2"));
        Exchange ex       = exchange(t, "APPROVED", "Risk acceptable");

        reintegrator.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> feedback = (Map<String, Object>) ex.getIn().getBody();
        assertThat(feedback.get("reviewOutcome")).isEqualTo("APPROVED");
        assertThat(feedback.get("reviewNotes")).isEqualTo("Risk acceptable");
    }

    @Test
    @DisplayName("feedbackTaskId header set for file naming")
    void feedbackTaskId_headerSet() throws Exception {
        ScoringResult sr  = scoringResult("req-3", 0.5, 0.65);
        HumanReviewTask t = task("my-task-id", sr, artifact("req-3"));
        Exchange ex       = exchange(t, "REJECTED", "High risk confirmed");

        reintegrator.process(ex);

        assertThat(ex.getIn().getHeader("feedbackTaskId")).isEqualTo("my-task-id");
    }

    // ── Regression case ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Regression case nested in feedback contains humanVerdict + expectedDecision")
    void regressionCase_containsVerdict() throws Exception {
        ScoringResult sr  = scoringResult("req-4", 0.55, 0.65);
        HumanReviewTask t = task("task-004", sr, artifact("req-4"));
        Exchange ex       = exchange(t, "REJECTED", "Confirmed risk");

        reintegrator.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> feedback = (Map<String, Object>) ex.getIn().getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> regression = (Map<String, Object>) feedback.get("regressionCase");

        assertThat(regression)
                .containsKey("caseId")
                .containsKey("decisionId")
                .containsKey("humanVerdict")
                .containsKey("expectedDecision")
                .containsKey("score")
                .containsKey("featuresSnapshot")
                .containsKey("capturedAt");
        assertThat(regression.get("humanVerdict")).isEqualTo("REJECTED");
        assertThat(regression.get("expectedDecision")).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("Regression case featuresSnapshot contains features from ScoringResult")
    void regressionCase_containsFeatureSnapshot() throws Exception {
        ScoringResult sr  = new ScoringResult("req-5", "entity-1", 0.5, 0.65,
                "model", "REVIEW", Map.of("kyc_status", "VERIFIED", "age_days", 365), null);
        HumanReviewTask t = task("task-005", sr, artifact("req-5"));
        Exchange ex       = exchange(t, "APPROVED", "");

        reintegrator.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> feedback   = (Map<String, Object>) ex.getIn().getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> regression = (Map<String, Object>) feedback.get("regressionCase");
        @SuppressWarnings("unchecked")
        Map<String, Object> features   = (Map<String, Object>) regression.get("featuresSnapshot");
        assertThat(features).containsKey("kyc_status");
    }

    // ── Missing headers ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Missing reviewOutcome header — defaults to UNKNOWN gracefully")
    void missingOutcome_defaultsGracefully() throws Exception {
        ScoringResult sr  = scoringResult("req-6", 0.5, 0.65);
        HumanReviewTask t = task("task-006", sr, artifact("req-6"));
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(t);
        // No headers set

        reintegrator.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> feedback = (Map<String, Object>) ex.getIn().getBody();
        assertThat(feedback.get("reviewOutcome")).isEqualTo("UNKNOWN");
        assertThat(feedback).containsKey("feedbackAt");
    }
}
