package com.aibook.routes.scoring;

import com.aibook.ai.llm.LlmGateway;
import com.aibook.ai.llm.PromptLoader;
import com.aibook.ai.llm.StructuredOutputParser;
import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ScoringResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.scoring.processors.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration-style route test for the scoring pipeline:
 * {@code direct:scoreRequest} → contextual routing.
 *
 * <p>Strategy:
 * <ul>
 *   <li>All scoring route and processor beans are loaded.</li>
 *   <li>{@link LlmGateway} and {@link PromptLoader} are mocked.</li>
 *   <li>AdviceWith intercepts the three output routes with mock endpoints.</li>
 * </ul>
 */
@CamelSpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(
        classes = {
                ScoringTestApplication.class,
                ScoringRoute.class,
                ContextualRoutingRoute.class,
                // Processors
                ModelScoringProcessor.class,
                ConfidenceEvaluator.class,
                HumanReviewTaskBuilder.class,
                StructuredOutputParser.class,
                // Shared
                AiErrorHandler.class,
                AuditArtifactCollector.class
        },
        properties = {
                "aibook.scoring.confidence-threshold=0.6",
                "aibook.human-review.queue=direct:human-review",
                // Resilience4j circuit breaker config for tests (mirrors application.yml)
                "camel.resilience4j.configurations.llm-circuit-breaker.sliding-window-size=10",
                "camel.resilience4j.configurations.llm-circuit-breaker.failure-rate-threshold=50",
                "camel.resilience4j.configurations.llm-circuit-breaker.wait-duration-in-open-state=30000"
        }
)
@Import(ScoringRouteTest.TestConfig.class)
class ScoringRouteTest {

    /** Enables AiPipelineProperties binding without full application context. */
    @TestConfiguration
    @EnableConfigurationProperties(AiPipelineProperties.class)
    static class TestConfig {}

    @MockBean LlmGateway        llmGateway;
    @MockBean PromptLoader       promptLoader;
    @MockBean ObjectMapper       objectMapper;

    @Autowired org.apache.camel.CamelContext camelContext;

    ProducerTemplate producer;

    @EndpointInject("mock:approveFlow")
    MockEndpoint mockApprove;

    @EndpointInject("mock:reviewFlow")
    MockEndpoint mockReview;

    @EndpointInject("mock:escalateFlow")
    MockEndpoint mockEscalate;

    @BeforeEach
    void wireAdvice() throws Exception {
        AdviceWith.adviceWith(camelContext, "contextual-routing", a -> {
            a.weaveByToUri("direct:approveFlow").replace().to("mock:approveFlow");
            a.weaveByToUri("direct:reviewFlow").replace().to("mock:reviewFlow");
            a.weaveByToUri("direct:escalateFlow").replace().to("mock:escalateFlow");
        });
        producer = camelContext.createProducerTemplate();
    }

    private com.aibook.core.dto.ScoringRequest scoringRequest(
            String id, String entityId, Map<String, Object> features) {
        return new com.aibook.core.dto.ScoringRequest(id, entityId, null, null, features, null);
    }

    // ── APPROVE path ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("High confidence + low score → APPROVE flow")
    void highConfidenceLowScore_routesToApprove() throws Exception {
        // LLM returns score=0.2, confidence=0.92 → APPROVE
        when(llmGateway.generateFromTemplate(anyString(), anyMap()))
                .thenReturn("{\"score\": 0.2, \"confidence\": 0.92, \"reasoning\": \"Low risk customer.\"}");

        mockApprove.expectedMessageCount(1);
        mockReview.expectedMessageCount(0);
        mockEscalate.expectedMessageCount(0);

        producer.sendBody("direct:scoreRequest", scoringRequest("req-approve", "entity-clean",
                Map.of("account_age_days", 365, "kyc_status", "VERIFIED",
                       "failed_transactions_30d", 0)));

        mockApprove.assertIsSatisfied();

        ScoringResult result = mockApprove.getReceivedExchanges().get(0)
                .getIn().getBody(ScoringResult.class);
        assertThat(result.routingDecision()).isEqualTo("APPROVE");
        assertThat(result.score()).isLessThan(0.75);
        assertThat(result.confidence()).isGreaterThan(0.8);
    }

    // ── REVIEW path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Medium confidence → REVIEW flow")
    void mediumConfidence_routesToReview() throws Exception {
        // LLM returns score=0.4, confidence=0.65 → REVIEW
        when(llmGateway.generateFromTemplate(anyString(), anyMap()))
                .thenReturn("{\"score\": 0.4, \"confidence\": 0.65, \"reasoning\": \"Moderate risk.\"}");

        mockApprove.expectedMessageCount(0);
        mockReview.expectedMessageCount(1);
        mockEscalate.expectedMessageCount(0);

        producer.sendBody("direct:scoreRequest", scoringRequest("req-review", "entity-medium",
                Map.of("account_age_days", 60, "failed_transactions_30d", 3)));

        mockReview.assertIsSatisfied();

        String decision = mockReview.getReceivedExchanges().get(0)
                .getIn().getHeader("routingDecision", String.class);
        assertThat(decision).isEqualTo("REVIEW");
    }

    // ── ESCALATE path ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("High risk score → ESCALATE flow")
    void highRiskScore_routesToEscalate() throws Exception {
        // LLM returns score=0.85, confidence=0.9 → ESCALATE (score >= 0.75)
        when(llmGateway.generateFromTemplate(anyString(), anyMap()))
                .thenReturn("{\"score\": 0.85, \"confidence\": 0.9, \"reasoning\": \"High risk.\"}");

        mockApprove.expectedMessageCount(0);
        mockReview.expectedMessageCount(0);
        mockEscalate.expectedMessageCount(1);

        producer.sendBody("direct:scoreRequest", scoringRequest("req-escalate", "entity-risky",
                Map.of("account_age_days", 5, "failed_transactions_30d", 10,
                       "kyc_status", "UNVERIFIED")));

        mockEscalate.assertIsSatisfied();

        String decision = mockEscalate.getReceivedExchanges().get(0)
                .getIn().getHeader("routingDecision", String.class);
        assertThat(decision).isEqualTo("ESCALATE");
    }

    @Test
    @DisplayName("Low confidence → ESCALATE flow (rule-based fallback)")
    void lowConfidence_routesToEscalate() throws Exception {
        // LLM returns low confidence
        when(llmGateway.generateFromTemplate(anyString(), anyMap()))
                .thenReturn("{\"score\": 0.3, \"confidence\": 0.3, \"reasoning\": \"Uncertain.\"}");

        mockEscalate.expectedMessageCount(1);

        producer.sendBody("direct:scoreRequest", scoringRequest("req-low-conf", "entity-uncertain",
                Map.of("account_age_days", 30)));

        mockEscalate.assertIsSatisfied();
    }

    // ── Audit record ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Audit trail is created on exchange property after contextual routing")
    void auditTrail_createdAfterRouting() throws Exception {
        when(llmGateway.generateFromTemplate(anyString(), anyMap()))
                .thenReturn("{\"score\": 0.2, \"confidence\": 0.92, \"reasoning\": \"Clean.\"}");

        mockApprove.expectedMessageCount(1);
        producer.sendBody("direct:scoreRequest", scoringRequest("req-audit", "entity-audit",
                Map.of("account_age_days", 200)));

        mockApprove.assertIsSatisfied();

        var auditTrail = mockApprove.getReceivedExchanges().get(0)
                .getProperty("auditTrail");
        assertThat(auditTrail).isNotNull();
    }
}
