package com.aibook.routes.explanation.processors;

import com.aibook.core.config.AiPipelineProperties;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReviewTaskBuilder}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>HumanReviewTask is created when routingDecision == REVIEW</li>
 *   <li>Priority mapping from score</li>
 *   <li>ESCALATE routing decision forces at least HIGH priority</li>
 *   <li>reviewTaskId and priority headers set</li>
 *   <li>ExplanationArtifact preserved or auto-generated</li>
 * </ul>
 */
class ReviewTaskBuilderTest {

    private DefaultCamelContext camelContext;
    private ReviewTaskBuilder   builder;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();

        AiPipelineProperties.HumanReviewConfig hrCfg =
                new AiPipelineProperties.HumanReviewConfig("direct:human-review", "MEDIUM",
                        new AiPipelineProperties.HumanReviewConfig.SimulationConfig(0L));
        AiPipelineProperties props = new AiPipelineProperties(
                null, null, null, null, null, hrCfg);
        builder = new ReviewTaskBuilder(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(ScoringResult body) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        return ex;
    }

    private ScoringResult scoringResult(String requestId, double score,
                                        double confidence, String decision) {
        return new ScoringResult(requestId, "entity-1", score, confidence,
                "test-model", decision, Map.of("kyc", "VERIFIED"), null);
    }

    // ── Task creation when routingDecision == REVIEW ──────────────────────────

    @Test
    @DisplayName("HumanReviewTask created correctly from ScoringResult body")
    void task_createdFromScoringResult() throws Exception {
        Exchange ex = exchange(scoringResult("req-1", 0.5, 0.65, "REVIEW"));
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task).isNotNull();
        assertThat(task.decisionId()).isEqualTo("req-1");
        assertThat(task.scoringResult()).isNotNull();
        assertThat(task.reviewQueue()).isEqualTo("direct:human-review");
    }

    @Test
    @DisplayName("reviewTaskId header set to a non-blank UUID")
    void reviewTaskId_setAsUuid() throws Exception {
        Exchange ex = exchange(scoringResult("req-2", 0.5, 0.65, "REVIEW"));
        builder.process(ex);

        String taskId = ex.getIn().getHeader("reviewTaskId", String.class);
        assertThat(taskId).isNotBlank();
        assertThat(taskId).matches("[0-9a-f-]{36}");
    }

    // ── Priority mapping ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "score={0} decision={1} → priority={2}")
    @CsvSource({
            "0.80, REVIEW,   URGENT",
            "0.75, REVIEW,   URGENT",
            "0.74, REVIEW,   HIGH",
            "0.60, REVIEW,   HIGH",
            "0.59, REVIEW,   MEDIUM",
            "0.40, REVIEW,   MEDIUM",
            "0.39, REVIEW,   LOW",
            "0.10, REVIEW,   LOW",
    })
    void priorityMapping_fromScore(double score, String decision, String expectedPriority)
            throws Exception {
        Exchange ex = exchange(scoringResult("req-p", score, 0.65, decision));
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo(expectedPriority);
        assertThat(ex.getIn().getHeader("priority")).isEqualTo(expectedPriority);
    }

    @Test
    @DisplayName("ESCALATE routing decision forces at least HIGH priority")
    void escalate_forcesHighPriority() throws Exception {
        // score=0.3 would normally be LOW, but ESCALATE forces to HIGH
        Exchange ex = exchange(scoringResult("req-e", 0.3, 0.65, "ESCALATE"));
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("ESCALATE + score >= 0.75 stays URGENT")
    void escalate_staysUrgentWhenHighScore() throws Exception {
        Exchange ex = exchange(scoringResult("req-eu", 0.85, 0.65, "ESCALATE"));
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo("URGENT");
    }

    // ── ExplanationArtifact handling ──────────────────────────────────────────

    @Test
    @DisplayName("Pre-existing ExplanationArtifact on exchange property is used in task")
    void existingArtifact_usedInTask() throws Exception {
        ExplanationArtifact artifact = new ExplanationArtifact(
                "req-3", Map.of("feature", 1), Map.of("score", 0.5),
                "Custom rationale.", "model", null);
        Exchange ex = exchange(scoringResult("req-3", 0.5, 0.65, "REVIEW"));
        ex.setProperty("explanationArtifact", artifact);

        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.explanation()).isNotNull();
        assertThat(task.explanation().rationale()).isEqualTo("Custom rationale.");
    }

    @Test
    @DisplayName("Auto-generated ExplanationArtifact used when none on exchange")
    void noArtifact_autoGenerated() throws Exception {
        Exchange ex = exchange(scoringResult("req-4", 0.5, 0.65, "REVIEW"));
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.explanation()).isNotNull();
        assertThat(task.explanation().rationale()).isNotBlank();
    }

    // ── HumanReviewTask forwarded ─────────────────────────────────────────────

    @Test
    @DisplayName("Incoming HumanReviewTask body is handled and decisionId preserved")
    void incomingTask_handled() throws Exception {
        ScoringResult sr = scoringResult("req-5", 0.55, 0.65, "REVIEW");
        HumanReviewTask incomingTask = new HumanReviewTask(
                null, "req-5", null, sr, "direct:old-queue", "MEDIUM", null);
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(incomingTask);

        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.decisionId()).isEqualTo("req-5");
    }
}
