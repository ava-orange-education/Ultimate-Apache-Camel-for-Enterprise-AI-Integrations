package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.AuditRecord;
import com.aibook.core.dto.ExplanationArtifact;
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
 * Unit tests for {@link AuditRecordSerializer}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Wrapper document shape (decisionId, capturedAt, artifact, auditTrail)</li>
 *   <li>auditStepCount matches trail size</li>
 *   <li>auditDecisionId header set for file naming</li>
 *   <li>Null/absent audit trail handled gracefully</li>
 *   <li>Null artifact body produces placeholder</li>
 * </ul>
 */
class AuditRecordSerializerTest {

    private DefaultCamelContext     camelContext;
    private AuditRecordSerializer   serializer;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        serializer = new AuditRecordSerializer();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(ExplanationArtifact artifact, List<AuditRecord> trail) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(artifact);
        if (trail != null) ex.setProperty("auditTrail", trail);
        return ex;
    }

    private ExplanationArtifact artifact(String decisionId) {
        return new ExplanationArtifact(decisionId,
                Map.of("score", 0.5), Map.of("routingDecision", "REVIEW"),
                "Test narrative.", "test-model", null);
    }

    private AuditRecord auditRecord(String decisionId, String stage) {
        return new AuditRecord(null, decisionId, stage, Map.of(), Map.of(), "model", null);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Wrapper document contains decisionId, capturedAt, artifact, auditTrail")
    void wrapperDocument_hasExpectedKeys() throws Exception {
        List<AuditRecord> trail = List.of(
                auditRecord("dec-1", "scoring"),
                auditRecord("dec-1", "graph"));
        Exchange ex = exchange(artifact("dec-1"), new ArrayList<>(trail));

        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) ex.getIn().getBody();
        assertThat(wrapper).containsKeys("decisionId", "capturedAt", "artifact",
                "auditTrail", "auditStepCount", "modelVersion");
    }

    @Test
    @DisplayName("auditStepCount matches the size of the provided audit trail")
    void auditStepCount_matchesTrailSize() throws Exception {
        List<AuditRecord> trail = new ArrayList<>(List.of(
                auditRecord("dec-2", "step-1"),
                auditRecord("dec-2", "step-2"),
                auditRecord("dec-2", "step-3")));
        Exchange ex = exchange(artifact("dec-2"), trail);

        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) ex.getIn().getBody();
        assertThat(wrapper.get("auditStepCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("auditDecisionId header set from artifact.decisionId")
    void auditDecisionId_headerSet() throws Exception {
        Exchange ex = exchange(artifact("decision-xyz"), new ArrayList<>());
        serializer.process(ex);
        assertThat(ex.getIn().getHeader("auditDecisionId")).isEqualTo("decision-xyz");
    }

    @Test
    @DisplayName("Artifact inner document preserved with all fields")
    void artifactDocument_preservedInWrapper() throws Exception {
        ExplanationArtifact a = artifact("dec-3");
        Exchange ex = exchange(a, new ArrayList<>());
        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper  = (Map<String, Object>) ex.getIn().getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) wrapper.get("artifact");
        assertThat(artifact)
                .containsKey("decisionId")
                .containsKey("evidence")
                .containsKey("signals")
                .containsKey("rationale")
                .containsKey("modelVersion");
        assertThat(artifact.get("rationale")).isEqualTo("Test narrative.");
    }

    // ── Null / edge cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Null audit trail property — auditStepCount = 0")
    void nullAuditTrail_zeroStepCount() throws Exception {
        Exchange ex = exchange(artifact("dec-4"), null);   // no trail property
        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) ex.getIn().getBody();
        assertThat(wrapper.get("auditStepCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("Null body — placeholder artifact created, no exception")
    void nullBody_placeholderCreated() throws Exception {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);
        ex.setProperty("decisionId", "fallback-id");

        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) ex.getIn().getBody();
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get("decisionId")).isEqualTo("fallback-id");
    }

    @Test
    @DisplayName("Audit trail records serialised to list of maps with expected keys")
    void auditTrailRecords_serialisedCorrectly() throws Exception {
        List<AuditRecord> trail = new ArrayList<>(List.of(
                auditRecord("dec-5", "feature-assembly")));
        Exchange ex = exchange(artifact("dec-5"), trail);
        serializer.process(ex);

        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) ex.getIn().getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> serialisedTrail = (List<Map<String, Object>>) wrapper.get("auditTrail");
        assertThat(serialisedTrail).hasSize(1);
        assertThat(serialisedTrail.get(0))
                .containsKey("auditId")
                .containsKey("decisionId")
                .containsKey("pipelineStage")
                .containsKey("timestamp");
        assertThat(serialisedTrail.get(0).get("pipelineStage")).isEqualTo("feature-assembly");
    }
}
