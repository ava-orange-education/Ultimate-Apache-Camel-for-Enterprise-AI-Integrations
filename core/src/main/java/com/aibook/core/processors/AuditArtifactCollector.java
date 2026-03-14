package com.aibook.core.processors;

import com.aibook.core.dto.AuditRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an {@link AuditRecord} from exchange state and appends it to the
 * {@code auditTrail} exchange property (a {@code List<AuditRecord>}).
 *
 * <p>Reads from exchange <em>properties</em> (not headers):
 * <ul>
 *   <li>{@code decisionId}   – identifier of the AI decision being audited</li>
 *   <li>{@code stage}        – pipeline stage name (e.g. "summarization", "scoring")</li>
 *   <li>{@code modelVersion} – LLM / model version string</li>
 * </ul>
 *
 * <p>Builds {@code inputs} from all current exchange headers (snapshot).
 * Builds {@code outputs} as a JSON-safe snapshot of the exchange body.
 */
@Component
public class AuditArtifactCollector implements Processor {

    private static final Logger log = LoggerFactory.getLogger(AuditArtifactCollector.class);

    private final ObjectMapper objectMapper;

    public AuditArtifactCollector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) {
        // ── Read from exchange properties ─────────────────────────────────────
        String decisionId   = exchange.getProperty("decisionId",   "unknown", String.class);
        String stage        = exchange.getProperty("stage",        "unknown", String.class);
        String modelVersion = exchange.getProperty("modelVersion", "unknown", String.class);

        // ── Snapshot inputs: all current headers ──────────────────────────────
        Map<String, Object> inputs = new HashMap<>();
        exchange.getIn().getHeaders().forEach((k, v) -> inputs.put(k, safeString(v)));

        // ── Snapshot outputs: current body ────────────────────────────────────
        Map<String, Object> outputs = Map.of("body", safeString(exchange.getIn().getBody()));

        // ── Build the AuditRecord ─────────────────────────────────────────────
        AuditRecord record = new AuditRecord(
                null,          // auditId auto-generated
                decisionId,
                stage,
                inputs,
                outputs,
                modelVersion,
                null           // timestamp auto-generated
        );

        // ── Append to auditTrail property (create list if absent) ─────────────
        @SuppressWarnings("unchecked")
        List<AuditRecord> auditTrail =
                (List<AuditRecord>) exchange.getProperty("auditTrail", List.class);
        if (auditTrail == null) {
            auditTrail = new ArrayList<>();
            exchange.setProperty("auditTrail", auditTrail);
        }
        auditTrail.add(record);

        log.debug("AuditArtifactCollector: appended record auditId={} decisionId={} stage={} "
                + "(trail size={})",
                record.auditId(), decisionId, stage, auditTrail.size());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String safeString(Object obj) {
        if (obj == null) return "";
        try {
            String json = objectMapper.writeValueAsString(obj);
            return json.length() > 500 ? json.substring(0, 500) + "…" : json;
        } catch (Exception e) {
            String s = obj.toString();
            return s.length() > 500 ? s.substring(0, 500) + "…" : s;
        }
    }
}