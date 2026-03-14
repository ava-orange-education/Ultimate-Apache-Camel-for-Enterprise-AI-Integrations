package com.aibook.routes.scoring.processors;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link HumanReviewTaskBuilder}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Task priority maps correctly from routingDecision</li>
 *   <li>HumanReviewTask body is populated with ScoringResult and explanation</li>
 *   <li>reviewTaskId and reviewQueue headers are set</li>
 *   <li>Explanation artifact is auto-generated when not present on exchange</li>
 * </ul>
 */
class HumanReviewTaskBuilderTest {

    private DefaultCamelContext   camelContext;
    private HumanReviewTaskBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();

        AiPipelineProperties.HumanReviewConfig hrCfg =
                new AiPipelineProperties.HumanReviewConfig("direct:human-review", "MEDIUM",
                        new AiPipelineProperties.HumanReviewConfig.SimulationConfig(0L));
        AiPipelineProperties props = new AiPipelineProperties(
                null, null, null, null, null, hrCfg);
        builder = new HumanReviewTaskBuilder(props);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(ScoringResult result, String routingDecision) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(result);
        ex.getIn().setHeader("routingDecision", routingDecision);
        return ex;
    }

    private ScoringResult scoringResult(String id, double score, double confidence) {
        return new ScoringResult(id, "entity-1", score, confidence,
                "test-model", "PENDING", Map.of("kyc_status", "VERIFIED"), null);
    }

    // ── Priority mapping ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ESCALATE decision → URGENT priority")
    void escalate_urgentPriority() throws Exception {
        Exchange ex = exchange(scoringResult("req-1", 0.8, 0.3), "ESCALATE");
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo("URGENT");
        assertThat(ex.getIn().getHeader("priority")).isEqualTo("URGENT");
    }

    @Test
    @DisplayName("REVIEW + score > 0.6 → HIGH priority")
    void review_highScoreHighPriority() throws Exception {
        Exchange ex = exchange(scoringResult("req-2", 0.70, 0.65), "REVIEW");
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("REVIEW + score <= 0.6 → MEDIUM priority")
    void review_lowScoreMediumPriority() throws Exception {
        Exchange ex = exchange(scoringResult("req-3", 0.4, 0.65), "REVIEW");
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.priority()).isEqualTo("MEDIUM");
    }

    // ── Task fields ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("HumanReviewTask body contains the ScoringResult")
    void task_containsScoringResult() throws Exception {
        ScoringResult result = scoringResult("req-4", 0.5, 0.65);
        Exchange ex = exchange(result, "REVIEW");
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.scoringResult()).isEqualTo(result);
        assertThat(task.decisionId()).isEqualTo("req-4");
    }

    @Test
    @DisplayName("reviewTaskId header is set to a non-blank UUID")
    void reviewTaskId_setAsUuid() throws Exception {
        Exchange ex = exchange(scoringResult("req-5", 0.5, 0.65), "REVIEW");
        builder.process(ex);

        String taskId = ex.getIn().getHeader("reviewTaskId", String.class);
        assertThat(taskId).isNotBlank();
        assertThat(taskId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("reviewQueue header is set from configuration")
    void reviewQueue_setFromConfig() throws Exception {
        Exchange ex = exchange(scoringResult("req-6", 0.5, 0.65), "REVIEW");
        builder.process(ex);

        assertThat(ex.getIn().getHeader("reviewQueue")).isEqualTo("direct:human-review");
    }

    @Test
    @DisplayName("Explanation artifact is auto-generated when absent from exchange properties")
    void explanation_autoGeneratedWhenAbsent() throws Exception {
        Exchange ex = exchange(scoringResult("req-7", 0.5, 0.65), "REVIEW");
        // No explanationArtifact property set
        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.explanation()).isNotNull();
        assertThat(task.explanation().rationale()).contains("REVIEW");
    }

    @Test
    @DisplayName("Pre-existing ExplanationArtifact on exchange is used in task")
    void explanation_usedFromExchangeProperty() throws Exception {
        ExplanationArtifact artifact = new ExplanationArtifact(
                "req-8", Map.of("feature", "value"), Map.of("score", 0.5),
                "Custom rationale.", "test-model", null);

        Exchange ex = exchange(scoringResult("req-8", 0.5, 0.65), "REVIEW");
        ex.setProperty("explanationArtifact", artifact);

        builder.process(ex);

        HumanReviewTask task = ex.getIn().getBody(HumanReviewTask.class);
        assertThat(task.explanation().rationale()).isEqualTo("Custom rationale.");
    }

    // ── Null body ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Null body throws IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> builder.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HumanReviewTaskBuilder");
    }
}
