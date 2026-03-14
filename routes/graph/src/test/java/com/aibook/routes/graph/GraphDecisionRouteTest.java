package com.aibook.routes.graph;

import com.aibook.ai.graph.GraphTraversalService;
import com.aibook.ai.graph.Neo4jGraphClient;
import com.aibook.core.config.AiPipelineProperties;
import com.aibook.core.dto.ScoringResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.graph.processors.GraphAwareDecisionMaker;
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

/**
 * Integration-style route test for the graph decision pipeline.
 * Sends pre-enriched messages directly to {@code direct:graphDecision}
 * with graph signal headers already set (bypassing the enrichment REST chain).
 */
@CamelSpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(
        classes = {
                GraphTestApplication.class,
                GraphDecisionRoute.class,
                GraphAwareDecisionMaker.class,
                AiErrorHandler.class,
                AuditArtifactCollector.class
        },
        properties = {
                "aibook.graph.max-traversal-depth=3",
                "aibook.graph.risk-cluster-threshold=5",
                "aibook.graph.risk-strength-threshold=0.7",
                "aibook.graph.risk-density-threshold=0.8"
        }
)
@Import(GraphDecisionRouteTest.TestConfig.class)
class GraphDecisionRouteTest {

    @TestConfiguration
    @EnableConfigurationProperties(AiPipelineProperties.class)
    static class TestConfig {}

    @MockBean GraphTraversalService graphTraversalService;
    @MockBean Neo4jGraphClient      neo4jClient;
    @MockBean ObjectMapper          objectMapper;

    @Autowired org.apache.camel.CamelContext camelContext;

    ProducerTemplate producer;

    @EndpointInject("mock:escalateFlow")
    MockEndpoint mockEscalate;

    @EndpointInject("mock:contextualRouting")
    MockEndpoint mockContextual;

    @BeforeEach
    void wireAdvice() throws Exception {
        // Context is already started (no @UseAdviceWith). Weave graph-decision outputs.
        AdviceWith.adviceWith(camelContext, "graph-decision", a -> {
            a.weaveByToUri("direct:escalateFlow").replace().to("mock:escalateFlow");
            a.weaveByToUri("direct:contextualRouting").replace().to("mock:contextualRouting");
        });
        producer = camelContext.createProducerTemplate();
    }

    private ScoringResult scoringResult(double score, double confidence) {
        return new ScoringResult("req-1", "test-entity", score, confidence,
                "model", "REVIEW", Map.of(), null);
    }

    // ── ESCALATE path: risk cluster detected ──────────────────────────────────

    @Test
    @DisplayName("High connectedEntityCount + avgStrength → graph risk elevated → escalate")
    void graphRiskCluster_routesToEscalate() throws Exception {
        mockEscalate.expectedMessageCount(1);
        mockContextual.expectedMessageCount(0);

        // Send directly to graphDecision with graph signal headers pre-set
        producer.sendBodyAndHeaders("direct:graphDecision",
                scoringResult(0.55, 0.65),
                Map.of("entityId",               "test-entity",
                       "confidence",              0.65,
                       "score",                   0.55,
                       "connectedEntityCount",    8,
                       "avgRelationshipStrength", 0.85,
                       "clusterDensity",          0.5));

        mockEscalate.assertIsSatisfied();
        assertThat(mockEscalate.getReceivedExchanges().get(0)
                .getIn().getHeader("graphRiskElevated", Boolean.class)).isTrue();
    }

    // ── contextualRouting path: no graph risk ─────────────────────────────────

    @Test
    @DisplayName("Low connectedEntityCount → no graph risk → contextualRouting")
    void noGraphRisk_routesToContextual() throws Exception {
        mockEscalate.expectedMessageCount(0);
        mockContextual.expectedMessageCount(1);

        producer.sendBodyAndHeaders("direct:graphDecision",
                scoringResult(0.45, 0.65),
                Map.of("entityId",               "test-entity",
                       "confidence",              0.65,
                       "score",                   0.45,
                       "connectedEntityCount",    2,
                       "avgRelationshipStrength", 0.30,
                       "clusterDensity",          0.1));

        mockContextual.assertIsSatisfied();
        assertThat(mockContextual.getReceivedExchanges().get(0)
                .getIn().getHeader("graphRiskElevated", Boolean.class)).isFalse();
    }

    // ── Enrichment skipped path ───────────────────────────────────────────────

    @Test
    @DisplayName("High confidence + high score → enrichment skipped → contextualRouting")
    void enrichmentSkipped_routesToContextual() throws Exception {
        mockEscalate.expectedMessageCount(0);
        mockContextual.expectedMessageCount(1);

        // No graph signals set → decision maker sees all zero → no risk
        producer.sendBodyAndHeaders("direct:graphDecision",
                scoringResult(0.9, 0.95),
                Map.of("entityId",   "test-entity",
                       "confidence", 0.95,
                       "score",      0.9));

        mockContextual.assertIsSatisfied();
    }

    // ── Audit trail ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Audit trail is created after graph decision")
    void auditTrail_createdAfterDecision() throws Exception {
        mockContextual.expectedMessageCount(1);

        producer.sendBodyAndHeaders("direct:graphDecision",
                scoringResult(0.45, 0.65),
                Map.of("entityId",               "test-entity",
                       "confidence",              0.65,
                       "score",                   0.45,
                       "connectedEntityCount",    2,
                       "avgRelationshipStrength", 0.30,
                       "clusterDensity",          0.1));

        mockContextual.assertIsSatisfied();
        assertThat(mockContextual.getReceivedExchanges().get(0)
                .getProperty("auditTrail")).isNotNull();
    }
}
