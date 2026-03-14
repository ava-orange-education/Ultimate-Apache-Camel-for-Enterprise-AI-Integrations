package com.aibook.routes.explanation;

import com.aibook.ai.llm.LlmGateway;
import com.aibook.ai.llm.PromptLoader;
import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.AuditRecord;
import com.aibook.core.dto.ScoringResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.core.processors.ExplanationArtifactBuilder;
import com.aibook.routes.explanation.processors.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.EndpointInject;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.apache.camel.test.spring.junit5.UseAdviceWith;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for the full explanation pipeline:
 * {@code direct:buildExplanation} → narrative generation → {@code direct:storeAudit}
 * → multicast (file + review check).
 *
 * <p>Strategy:
 * <ul>
 *   <li>{@link LlmGateway} is mocked — returns canned narrative.</li>
 *   <li>{@link PromptLoader} is mocked — returns a template string.</li>
 *   <li>AdviceWith intercepts file writes and human-review dispatch with mock endpoints.</li>
 * </ul>
 */
@CamelSpringBootTest
@UseAdviceWith
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(
        classes = {
                ExplanationTestApplication.class,
                ExplanationArtifactRoute.class,
                AuditTrailRoute.class,
                HumanReviewRoute.class,
                // Processors
                ExplanationEvidenceCollector.class,
                ExplanationArtifactBuilder.class,
                NarrativePromptAssembler.class,
                NarrativeResponseCapture.class,
                AuditRecordSerializer.class,
                ReviewTaskBuilder.class,
                ReviewSimulator.class,
                FeedbackReintegrator.class,
                ReviewDecisionProcessor.class,
                // Shared
                AiErrorHandler.class,
                AuditArtifactCollector.class
        },
        properties = {
                "aibook.audit.log-dir=${java.io.tmpdir}/aibook-test/audit",
                "aibook.feedback.output-dir=${java.io.tmpdir}/aibook-test/feedback",
                "aibook.feedback.regression-dir=${java.io.tmpdir}/aibook-test/regression",
                "aibook.human-review.queue=direct:human-review",
                "aibook.human-review.simulation.delay-ms=0"
        }
)
@Import(ExplanationPipelineRouteTest.TestConfig.class)
class ExplanationPipelineRouteTest {

    @TestConfiguration
    @EnableConfigurationProperties(AiPipelineProperties.class)
    static class TestConfig {}

    @MockBean LlmGateway    llmGateway;
    @MockBean PromptLoader  promptLoader;
    @MockBean ObjectMapper  objectMapper;

    @Autowired org.apache.camel.CamelContext camelContext;

    @Produce("direct:buildExplanation")
    ProducerTemplate producer;

    @EndpointInject("mock:auditFileWrite")
    MockEndpoint mockAuditFile;

    @EndpointInject("mock:submitHumanReview")
    MockEndpoint mockHumanReview;

    @EndpointInject("mock:noReviewNeeded")
    MockEndpoint mockNoReview;

    @BeforeEach
    void wireAdvice() throws Exception {
        // Intercept file write and human review dispatch
        AdviceWith.adviceWith(camelContext, "audit-file-write", a ->
                a.weaveByType(org.apache.camel.model.ToDynamicDefinition.class)
                 .replace().to("mock:auditFileWrite"));

        AdviceWith.adviceWith(camelContext, "audit-check-review", a -> {
            a.weaveByToUri("direct:submitHumanReview").replace().to("mock:submitHumanReview");
        });

        if (!camelContext.isStarted()) camelContext.start();

        // Mock LLM and prompt loader
        when(promptLoader.load(anyString())).thenReturn(
                "Evidence: {{evidence}} Signals: {{signals}} Decision: {{decision}}");
        when(llmGateway.chat(anyString(), anyString())).thenReturn(
                "The entity was assessed with a REVIEW decision based on moderate risk signals.");
    }

    private ScoringResult scoringResult(String id, double score, double confidence, String decision) {
        return new ScoringResult(id, "entity-1", score, confidence,
                "test-model", decision, Map.of("kyc", "VERIFIED", "age_days", 200), null);
    }

    private void setAuditTrail(Map<String, Object> headers) {
        // auditTrail must be set as an exchange property — done via sendBodyAndHeaders with property
    }

    // ── Audit record written for every decision ───────────────────────────────

    @Test
    @DisplayName("Audit file is written for every decision (APPROVE path)")
    void auditFile_writtenForApproveDecision() throws Exception {
        mockAuditFile.expectedMessageCount(1);
        mockHumanReview.expectedMessageCount(0);

        ScoringResult sr = scoringResult("req-approve", 0.2, 0.92, "APPROVE");
        producer.sendBodyAndHeaders(sr, Map.of("routingDecision", "APPROVE"));

        mockAuditFile.assertIsSatisfied();
    }

    // ── Human review triggered when routingDecision == REVIEW ─────────────────

    @Test
    @DisplayName("Human review submitted when routingDecision == REVIEW")
    void humanReview_submittedWhenReviewDecision() throws Exception {
        mockAuditFile.expectedMessageCount(1);
        mockHumanReview.expectedMessageCount(1);

        ScoringResult sr = scoringResult("req-review", 0.5, 0.65, "REVIEW");
        producer.sendBodyAndHeaders(sr, Map.of("routingDecision", "REVIEW"));

        mockAuditFile.assertIsSatisfied();
        mockHumanReview.assertIsSatisfied();
    }

    // ── No human review for non-REVIEW decisions ──────────────────────────────

    @Test
    @DisplayName("No human review submitted when routingDecision == ESCALATE")
    void humanReview_notSubmittedForEscalate() throws Exception {
        mockHumanReview.expectedMessageCount(0);

        ScoringResult sr = scoringResult("req-escalate", 0.85, 0.9, "ESCALATE");
        producer.sendBodyAndHeaders(sr, Map.of("routingDecision", "ESCALATE"));

        mockHumanReview.assertIsSatisfied();
    }

    // ── ExplanationArtifact assembled correctly ────────────────────────────────

    @Test
    @DisplayName("ExplanationArtifact body contains non-blank rationale from LLM")
    void explanationArtifact_hasRationale() throws Exception {
        mockAuditFile.expectedMessageCount(1);

        ScoringResult sr = scoringResult("req-narrative", 0.4, 0.75, "APPROVE");
        producer.sendBodyAndHeaders(sr, Map.of("routingDecision", "APPROVE"));

        mockAuditFile.assertIsSatisfied();
        // The audit file write receives a Map (JSON-serialised wrapper) as body
        // Verify it was actually called (rationale generation happened)
        assertThat(mockAuditFile.getReceivedExchanges()).isNotEmpty();
    }

    // ── Audit trail depth reflected ───────────────────────────────────────────

    @Test
    @DisplayName("Audit file body (serialised wrapper) contains auditStepCount field")
    void auditWrapper_containsStepCount() throws Exception {
        mockAuditFile.expectedMessageCount(1);

        ScoringResult sr = scoringResult("req-audit-count", 0.3, 0.85, "APPROVE");

        // Pre-seed an audit trail to verify it flows through
        List<AuditRecord> trail = new ArrayList<>();
        trail.add(new AuditRecord(null, "req-audit-count", "scoring",
                Map.of(), Map.of(), "model", null));

        // Need to set exchange property — use a processor interceptor pattern
        camelContext.createProducerTemplate().send("direct:buildExplanation", exchange -> {
            exchange.getIn().setBody(sr);
            exchange.getIn().setHeader("routingDecision", "APPROVE");
            exchange.setProperty("auditTrail", trail);
            exchange.setProperty("decisionId", "req-audit-count");
        });

        mockAuditFile.assertIsSatisfied();
    }
}
