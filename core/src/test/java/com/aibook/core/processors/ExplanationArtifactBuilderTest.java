package com.aibook.core.processors;

import com.aibook.core.dto.ExplanationArtifact;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExplanationArtifactBuilder}.
 */
class ExplanationArtifactBuilderTest {

    private ExplanationArtifactBuilder builder;
    private DefaultCamelContext camelContext;

    @BeforeEach
    void setUp() {
        builder = new ExplanationArtifactBuilder();
        camelContext = new DefaultCamelContext();
    }

    private Exchange createExchange() {
        return new DefaultExchange(camelContext);
    }

    // ── Happy path: full properties set ──────────────────────────────────────

    @Test
    void process_buildsArtifactFromExchangeProperties() throws Exception {
        Map<String, Object> evidence = Map.of("score", 0.87, "chunks", 5);
        Map<String, Object> signals  = Map.of("confidence", "HIGH", "label", "APPROVED");

        Exchange exchange = createExchange();
        exchange.setProperty("decisionId",   "dec-xyz");
        exchange.setProperty("modelVersion", "llama3.2");
        exchange.setProperty("evidence",     evidence);
        exchange.setProperty("signals",      signals);
        exchange.getIn().setBody("This decision was approved because the score exceeded threshold.");

        builder.process(exchange);

        Object body = exchange.getIn().getBody();
        assertThat(body).isInstanceOf(ExplanationArtifact.class);

        ExplanationArtifact artifact = (ExplanationArtifact) body;
        assertThat(artifact.decisionId()).isEqualTo("dec-xyz");
        assertThat(artifact.modelVersion()).isEqualTo("llama3.2");
        assertThat(artifact.rationale()).contains("approved");
        assertThat(artifact.evidence()).containsKey("score");
        assertThat(artifact.signals()).containsKey("confidence");
        assertThat(artifact.capturedAt()).isNotNull();
    }

    // ── Rationale from body ───────────────────────────────────────────────────

    @Test
    void process_usesBodyAsRationale() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId", "dec-rationale");
        exchange.getIn().setBody("Custom rationale text from LLM.");

        builder.process(exchange);

        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        assertThat(artifact.rationale()).isEqualTo("Custom rationale text from LLM.");
    }

    @Test
    void process_nullBody_producesEmptyRationale() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId", "dec-null-body");
        exchange.getIn().setBody(null);

        builder.process(exchange);

        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        assertThat(artifact.rationale()).isEmpty();
    }

    // ── Missing properties fall back to defaults ──────────────────────────────

    @Test
    void process_missingDecisionId_usesUnknown() throws Exception {
        Exchange exchange = createExchange();
        exchange.getIn().setBody("Some rationale");

        builder.process(exchange);

        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        assertThat(artifact.decisionId()).isEqualTo("unknown");
        assertThat(artifact.modelVersion()).isEqualTo("unknown");
    }

    @Test
    void process_nullEvidenceAndSignals_producesEmptyMaps() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId", "dec-empty-maps");
        exchange.getIn().setBody("rationale");

        builder.process(exchange);

        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        assertThat(artifact.evidence()).isEmpty();
        assertThat(artifact.signals()).isEmpty();
    }

    // ── Body is replaced with artifact ───────────────────────────────────────

    @Test
    void process_replacesBodyWithArtifact() throws Exception {
        Exchange exchange = createExchange();
        exchange.setProperty("decisionId", "dec-replace");
        exchange.getIn().setBody("original string body");

        builder.process(exchange);

        // Body must now be ExplanationArtifact, not the original String
        assertThat(exchange.getIn().getBody()).isInstanceOf(ExplanationArtifact.class);
        assertThat(exchange.getIn().getBody()).isNotEqualTo("original string body");
    }
}