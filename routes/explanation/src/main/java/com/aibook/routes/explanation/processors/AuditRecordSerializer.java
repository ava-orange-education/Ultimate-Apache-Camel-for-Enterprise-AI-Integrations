package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.AuditRecord;
import com.aibook.core.dto.ExplanationArtifact;
import com.aibook.core.dto.SummaryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises the complete audit trail (exchange property {@code auditTrail}) together
 * with the {@link ExplanationArtifact} into a single JSON-serialisable wrapper map
 * that is placed as the exchange body, ready for file persistence.
 *
 * <h3>Output body shape</h3>
 * <pre>
 * {
 *   "decisionId":    "...",
 *   "capturedAt":    "ISO-8601",
 *   "artifact":      { ExplanationArtifact fields },
 *   "auditTrail":    [ { AuditRecord }, ... ],
 *   "auditStepCount": 3
 * }
 * </pre>
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@link ExplanationArtifact}</li>
 *   <li>Exchange property {@code auditTrail}: {@code List<AuditRecord>}</li>
 * </ul>
 *
 * <p>Writes body: {@code Map<String, Object>} (JSON-serialisable wrapper).
 * Sets header {@code auditDecisionId} for use by downstream file-naming.
 */
@Component
public class AuditRecordSerializer implements Processor {

    private static final Logger log = LoggerFactory.getLogger(AuditRecordSerializer.class);

    private final ObjectMapper objectMapper;

    public AuditRecordSerializer() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        if (artifact == null) {
            // Body may be a JSON String (after marshal().json()) — parse it as a raw Map
            // so we can extract SummaryResult fields without needing Jackson record support.
            SummaryResult summary = null;
            Object raw = exchange.getIn().getBody();
            String jsonStr = null;
            if (raw instanceof String s && !s.isBlank()) {
                jsonStr = s;
            } else if (raw instanceof byte[] bytes && bytes.length > 0) {
                jsonStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }

            if (jsonStr != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fields = objectMapper.readValue(jsonStr, Map.class);
                    String sourceId     = getString(fields, "sourceId");
                    String summaryText  = getString(fields, "summaryText");
                    String summaryType  = getString(fields, "summaryType");
                    String modelVersion = getString(fields, "modelVersion");
                    boolean validated   = Boolean.TRUE.equals(fields.get("validated"));
                    if (sourceId != null && summaryType != null) {
                        summary = new SummaryResult(sourceId, summaryText != null ? summaryText : "",
                                summaryType, java.time.Instant.now(), modelVersion != null ? modelVersion : "unknown", validated);
                    }
                } catch (Exception ignored) {
                    // Not a SummaryResult-shaped JSON — fall through to placeholder
                }
            }

            if (summary != null) {
                artifact = new ExplanationArtifact(
                        summary.sourceId(),
                        Map.of("summaryType",   summary.summaryType(),
                               "summaryLength", summary.summaryText().length(),
                               "validated",     summary.validated()),
                        Map.of("model",          summary.modelVersion(),
                               "generatedAt",   summary.generatedAt().toString()),
                        "Summarization completed: type=" + summary.summaryType()
                                + " sourceId=" + summary.sourceId()
                                + " validated=" + summary.validated(),
                        summary.modelVersion(),
                        null
                );
                log.info("AuditRecordSerializer: built ExplanationArtifact from SummaryResult for sourceId={}",
                        summary.sourceId());
            } else {
                // Last-resort placeholder so audit always writes something
                String decisionId = exchange.getProperty("decisionId", "unknown", String.class);
                artifact = new ExplanationArtifact(decisionId,
                        Map.of(), Map.of(), "no artifact", "unknown", null);
                log.warn("AuditRecordSerializer: body is not an ExplanationArtifact — using placeholder");
            }
        }

        List<AuditRecord> auditTrail = (List<AuditRecord>)
                exchange.getProperty("auditTrail", List.class);
        if (auditTrail == null) auditTrail = new ArrayList<>();

        // ── Build wrapper document ────────────────────────────────────────────
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("decisionId",    artifact.decisionId());
        wrapper.put("capturedAt",    artifact.capturedAt().toString());
        wrapper.put("modelVersion",  artifact.modelVersion());
        wrapper.put("artifact",      toMap(artifact));
        wrapper.put("auditTrail",    serialiseTrail(auditTrail));
        wrapper.put("auditStepCount", auditTrail.size());

        exchange.getIn().setBody(wrapper);
        exchange.getIn().setHeader("auditDecisionId", artifact.decisionId());

        log.info("AuditRecordSerializer: serialised decisionId={} auditSteps={}",
                artifact.decisionId(), auditTrail.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(ExplanationArtifact a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decisionId",   a.decisionId());
        m.put("evidence",     a.evidence());
        m.put("signals",      a.signals());
        m.put("rationale",    a.rationale());
        m.put("modelVersion", a.modelVersion());
        m.put("capturedAt",   a.capturedAt().toString());
        return m;
    }

    private List<Map<String, Object>> serialiseTrail(List<AuditRecord> trail) {
        List<Map<String, Object>> result = new ArrayList<>(trail.size());
        for (AuditRecord r : trail) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("auditId",       r.auditId());
            m.put("decisionId",    r.decisionId());
            m.put("pipelineStage", r.pipelineStage());
            m.put("modelVersion",  r.modelVersion());
            m.put("timestamp",     r.timestamp().toString());
            // Keep inputs/outputs compact
            m.put("inputs",  truncateMap(r.inputs()));
            m.put("outputs", truncateMap(r.outputs()));
            result.add(m);
        }
        return result;
    }

    private Map<String, Object> truncateMap(Map<String, Object> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            String s = v != null ? v.toString() : "";
            out.put(k, s.length() > 200 ? s.substring(0, 200) + "…" : s);
        });
        return out;
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
