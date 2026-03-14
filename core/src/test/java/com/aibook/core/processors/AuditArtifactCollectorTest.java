package com.aibook.core.processors;

import com.aibook.core.dto.AuditRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuditArtifactCollector}.
 */
class AuditArtifactCollectorTest {

    private AuditArtifactCollector collector;
    private DefaultCamelContext camelContext;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        collector = new AuditArtifactCollector(mapper);
        camelContext = new DefaultCamelContext();
    }

    private Exchange createExchange() {
        return new DefaultExchange(camelContext);
    }

    // ── Creates and populates AuditRecord ────────────────────────────────────

    @Test
    void process_createsAuditRecordFromExchangeProperties() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId",   "dec-001");
        exchange.setProperty("stage",        "summarization");
        exchange.setProperty("modelVersion", "llama3.2");
        exchange.getIn().setBody("some output body");

        collector.process(exchange);

        @SuppressWarnings("unchecked")
        List<AuditRecord> trail = (List<AuditRecord>) exchange.getProperty("auditTrail");
        assertThat(trail).hasSize(1);

        AuditRecord record = trail.get(0);
        assertThat(record.decisionId()).isEqualTo("dec-001");
        assertThat(record.pipelineStage()).isEqualTo("summarization");
        assertThat(record.modelVersion()).isEqualTo("llama3.2");
        assertThat(record.auditId()).isNotBlank();
        assertThat(record.timestamp()).isNotNull();
    }

    @Test
    void process_outputsSnapshotOfBody() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId",   "dec-002");
        exchange.setProperty("stage",        "scoring");
        exchange.setProperty("modelVersion", "gpt-4o-mini");
        exchange.getIn().setBody("scoring output payload");

        collector.process(exchange);

        @SuppressWarnings("unchecked")
        List<AuditRecord> trail = (List<AuditRecord>) exchange.getProperty("auditTrail");
        AuditRecord record = trail.get(0);
        assertThat(record.outputs()).containsKey("body");
        assertThat(record.outputs().get("body").toString()).contains("scoring output payload");
    }

    @Test
    void process_inputsSnapshotIncludesHeaders() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId",   "dec-003");
        exchange.setProperty("stage",        "rag");
        exchange.setProperty("modelVersion", "llama3.2");
        exchange.getIn().setHeader("customHeader", "headerValue");
        exchange.getIn().setBody("rag body");

        collector.process(exchange);

        @SuppressWarnings("unchecked")
        List<AuditRecord> trail = (List<AuditRecord>) exchange.getProperty("auditTrail");
        AuditRecord record = trail.get(0);
        assertThat(record.inputs()).containsKey("customHeader");
    }

    // ── Appends to existing trail ─────────────────────────────────────────────

    @Test
    void process_appendsToExistingAuditTrail() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId",   "dec-004");
        exchange.setProperty("stage",        "stage-1");
        exchange.setProperty("modelVersion", "v1");
        exchange.getIn().setBody("body-1");

        collector.process(exchange);  // first call

        exchange.setProperty("stage", "stage-2");
        exchange.getIn().setBody("body-2");

        collector.process(exchange);  // second call

        @SuppressWarnings("unchecked")
        List<AuditRecord> trail = (List<AuditRecord>) exchange.getProperty("auditTrail");
        assertThat(trail).hasSize(2);
        assertThat(trail.get(0).pipelineStage()).isEqualTo("stage-1");
        assertThat(trail.get(1).pipelineStage()).isEqualTo("stage-2");
    }

    // ── Missing properties use defaults ───────────────────────────────────────

    @Test
    void process_missingProperties_usesDefaultUnknownValues() throws Exception {
        Exchange exchange = createExchange();
        // No properties set
        exchange.getIn().setBody("body");

        collector.process(exchange);

        @SuppressWarnings("unchecked")
        List<AuditRecord> trail = (List<AuditRecord>) exchange.getProperty("auditTrail");
        AuditRecord record = trail.get(0);
        assertThat(record.decisionId()).isEqualTo("unknown");
        assertThat(record.pipelineStage()).isEqualTo("unknown");
        assertThat(record.modelVersion()).isEqualTo("unknown");
    }
}