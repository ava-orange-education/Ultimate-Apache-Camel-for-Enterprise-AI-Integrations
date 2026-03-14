package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.AuditRecord;
import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.ScoringResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the {@code evidence} and {@code signals} maps on the exchange
 * properties — and enriches the LLM prompt variables — before the core
 * {@link com.aibook.core.processors.ExplanationArtifactBuilder} runs.
 *
 * <h3>Evidence assembled from</h3>
 * <ul>
 *   <li>{@link ScoringResult#featuresUsed()} from exchange body</li>
 *   <li>Exchange property {@code retrievedChunks} (List&lt;String&gt;) — RAG chunks used</li>
 *   <li>Exchange property {@code graphContext} — graph traversal results</li>
 * </ul>
 *
 * <h3>Signals assembled from</h3>
 * <ul>
 *   <li>{@code score}, {@code confidence} — from ScoringResult</li>
 *   <li>{@code routingDecision} — from ScoringResult</li>
 *   <li>Graph signal headers: {@code connectedEntityCount}, {@code avgRelationshipStrength}</li>
 *   <li>Audit trail size from exchange property {@code auditTrail}</li>
 * </ul>
 *
 * <p>After this processor runs, the exchange properties {@code evidence},
 * {@code signals}, {@code decisionId}, and {@code modelVersion} are populated
 * for the core {@code ExplanationArtifactBuilder} to consume.
 */
@Component
public class ExplanationEvidenceCollector implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ExplanationEvidenceCollector.class);

    private final ObjectMapper objectMapper;

    public ExplanationEvidenceCollector() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        ScoringResult scoring = resolveResult(exchange);

        // ── Assemble evidence map ─────────────────────────────────────────────
        Map<String, Object> evidence = new HashMap<>();

        if (scoring != null) {
            evidence.put("featuresUsed", scoring.featuresUsed());
            evidence.put("entityId",     scoring.entityId());
            evidence.put("requestId",    scoring.requestId());
        }

        // RAG chunks
        Object chunks = exchange.getProperty("retrievedChunks");
        if (chunks instanceof List<?> chunkList && !chunkList.isEmpty()) {
            evidence.put("retrievedChunkCount", chunkList.size());
            evidence.put("retrievedChunks",     chunkList.subList(0, Math.min(3, chunkList.size())));
        }

        // Graph paths
        Object graphCtx = exchange.getProperty("graphContext");
        if (graphCtx != null) {
            evidence.put("graphContext", graphCtx.toString());
        }

        // ── Assemble signals map ──────────────────────────────────────────────
        Map<String, Object> signals = new HashMap<>();

        if (scoring != null) {
            signals.put("score",           scoring.score());
            signals.put("confidence",      scoring.confidence());
            signals.put("routingDecision", scoring.routingDecision());
            signals.put("modelVersion",    scoring.modelVersion());
        }

        // Graph signals from headers
        addIfPresent(signals, "connectedEntityCount",    exchange.getIn().getHeader("connectedEntityCount"));
        addIfPresent(signals, "avgRelationshipStrength", exchange.getIn().getHeader("avgRelationshipStrength"));
        addIfPresent(signals, "clusterDensity",          exchange.getIn().getHeader("clusterDensity"));
        addIfPresent(signals, "graphRiskElevated",       exchange.getIn().getHeader("graphRiskElevated"));

        // Audit trail depth
        List<AuditRecord> auditTrail = (List<AuditRecord>) exchange.getProperty("auditTrail", List.class);
        signals.put("auditTrailDepth", auditTrail != null ? auditTrail.size() : 0);

        // ── Write to exchange properties for ExplanationArtifactBuilder ───────
        String decisionId   = resolveDecisionId(exchange, scoring);
        String modelVersion = scoring != null ? scoring.modelVersion() : "unknown";

        exchange.setProperty("evidence",     evidence);
        exchange.setProperty("signals",      signals);
        exchange.setProperty("decisionId",   decisionId);
        exchange.setProperty("modelVersion", modelVersion);

        log.info("ExplanationEvidenceCollector: decisionId={} evidenceKeys={} signalKeys={}",
                decisionId, evidence.keySet(), signals.keySet());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScoringResult resolveResult(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        if (body instanceof ScoringResult sr) return sr;
        // Also check header (set by scoring pipeline)
        return exchange.getIn().getHeader("ScoringResult", ScoringResult.class);
    }

    private String resolveDecisionId(Exchange exchange, ScoringResult scoring) {
        String fromProperty = exchange.getProperty("decisionId", String.class);
        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
        if (scoring != null && !scoring.requestId().isBlank()) return scoring.requestId();
        return "unknown";
    }

    private void addIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}
