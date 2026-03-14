package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.AuditRecord;
import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExplanationEvidenceCollector}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Evidence assembled from ScoringResult.featuresUsed</li>
 *   <li>Signals assembled from score, confidence, routingDecision</li>
 *   <li>Exchange properties decisionId and modelVersion set correctly</li>
 *   <li>Graph signal headers picked up when present</li>
 *   <li>Audit trail depth reflected in signals</li>
 * </ul>
 */
class ExplanationEvidenceCollectorTest {

    private DefaultCamelContext           camelContext;
    private ExplanationEvidenceCollector  collector;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        collector = new ExplanationEvidenceCollector();
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

    private ScoringResult scoringResult(String requestId, double score, double confidence) {
        return new ScoringResult(requestId, "entity-1", score, confidence,
                "test-model", "REVIEW", Map.of("kyc_status", "VERIFIED", "age", 365), null);
    }

    // ── Evidence assembly ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Evidence map contains featuresUsed from ScoringResult")
    void evidence_containsFeaturesUsed() throws Exception {
        Exchange ex = exchange(scoringResult("req-1", 0.5, 0.65));
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) ex.getProperty("evidence");
        assertThat(evidence).isNotNull();
        assertThat(evidence).containsKey("featuresUsed");
        assertThat(evidence).containsKey("entityId");
        assertThat(evidence).containsKey("requestId");
    }

    @Test
    @DisplayName("Evidence map includes retrieved chunks when present as property")
    void evidence_containsRetrievedChunks() throws Exception {
        Exchange ex = exchange(scoringResult("req-2", 0.5, 0.65));
        ex.setProperty("retrievedChunks", List.of("chunk1", "chunk2", "chunk3"));
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) ex.getProperty("evidence");
        assertThat(evidence).containsKey("retrievedChunkCount");
        assertThat(evidence.get("retrievedChunkCount")).isEqualTo(3);
    }

    // ── Signals assembly ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Signals map contains score, confidence, routingDecision, modelVersion")
    void signals_containsCoreFields() throws Exception {
        Exchange ex = exchange(scoringResult("req-3", 0.55, 0.70));
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) ex.getProperty("signals");
        assertThat(signals).containsKey("score");
        assertThat(signals).containsKey("confidence");
        assertThat(signals).containsKey("routingDecision");
        assertThat(signals).containsKey("modelVersion");
        assertThat(signals.get("score")).isEqualTo(0.55);
        assertThat(signals.get("routingDecision")).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("Signals map includes graph signals from exchange headers")
    void signals_containsGraphSignals() throws Exception {
        Exchange ex = exchange(scoringResult("req-4", 0.5, 0.65));
        ex.getIn().setHeader("connectedEntityCount",    6);
        ex.getIn().setHeader("avgRelationshipStrength", 0.82);
        ex.getIn().setHeader("graphRiskElevated",       true);
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) ex.getProperty("signals");
        assertThat(signals).containsKey("connectedEntityCount");
        assertThat(signals).containsKey("avgRelationshipStrength");
        assertThat(signals).containsKey("graphRiskElevated");
    }

    @Test
    @DisplayName("Signals map includes auditTrailDepth from exchange property")
    void signals_containsAuditTrailDepth() throws Exception {
        Exchange ex = exchange(scoringResult("req-5", 0.5, 0.65));
        List<AuditRecord> trail = new ArrayList<>();
        trail.add(new AuditRecord(null, "req-5", "scoring", Map.of(), Map.of(), "model", null));
        trail.add(new AuditRecord(null, "req-5", "graph",   Map.of(), Map.of(), "model", null));
        ex.setProperty("auditTrail", trail);
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) ex.getProperty("signals");
        assertThat(signals.get("auditTrailDepth")).isEqualTo(2);
    }

    // ── Exchange properties ───────────────────────────────────────────────────

    @Test
    @DisplayName("decisionId property resolved from ScoringResult.requestId")
    void decisionId_resolvedFromScoringResult() throws Exception {
        Exchange ex = exchange(scoringResult("my-request-id", 0.4, 0.8));
        collector.process(ex);
        assertThat(ex.getProperty("decisionId")).isEqualTo("my-request-id");
    }

    @Test
    @DisplayName("decisionId property prefers existing exchange property over body")
    void decisionId_prefersExistingProperty() throws Exception {
        Exchange ex = exchange(scoringResult("from-body-id", 0.4, 0.8));
        ex.setProperty("decisionId", "pre-existing-id");
        collector.process(ex);
        assertThat(ex.getProperty("decisionId")).isEqualTo("pre-existing-id");
    }

    @Test
    @DisplayName("modelVersion property set from ScoringResult.modelVersion")
    void modelVersion_setFromScoringResult() throws Exception {
        Exchange ex = exchange(scoringResult("req-6", 0.4, 0.8));
        collector.process(ex);
        assertThat(ex.getProperty("modelVersion")).isEqualTo("test-model");
    }

    // ── Null / missing body ───────────────────────────────────────────────────

    @Test
    @DisplayName("Null body — properties still populated with empty defaults")
    void nullBody_defaultsUsed() throws Exception {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) ex.getProperty("evidence");
        @SuppressWarnings("unchecked")
        Map<String, Object> signals  = (Map<String, Object>) ex.getProperty("signals");
        assertThat(evidence).isNotNull();
        assertThat(signals).isNotNull();
        assertThat(signals).containsKey("auditTrailDepth");
    }

    @Test
    @DisplayName("ScoringResult in header fallback used when body is not ScoringResult")
    void scoringResultFromHeader_fallback() throws Exception {
        ScoringResult sr = scoringResult("req-7", 0.6, 0.75);
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody("some string body");
        ex.getIn().setHeader("ScoringResult", sr);
        collector.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> signals = (Map<String, Object>) ex.getProperty("signals");
        assertThat(signals.get("score")).isEqualTo(0.6);
    }
}
