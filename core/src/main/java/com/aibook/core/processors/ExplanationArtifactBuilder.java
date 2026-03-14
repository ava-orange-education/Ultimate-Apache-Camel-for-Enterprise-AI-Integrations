package com.aibook.core.processors;

import com.aibook.core.dto.ExplanationArtifact;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Builds an {@link ExplanationArtifact} from exchange state and sets it as the exchange body.
 *
 * <p>Reads from exchange <em>properties</em>:
 * <ul>
 *   <li>{@code decisionId}   – the decision being explained (String)</li>
 *   <li>{@code evidence}     – raw feature / retrieval data ({@code Map<String,Object>})</li>
 *   <li>{@code signals}      – derived weighted signals ({@code Map<String,Object>})</li>
 *   <li>{@code modelVersion} – LLM / model version string</li>
 * </ul>
 *
 * <p>{@code rationale} is read from the exchange <em>body</em> (expected to be the
 * LLM-generated narrative string placed there by the explanation route's LLM call).
 * After processing the exchange body is replaced with the completed {@link ExplanationArtifact}.
 */
@Component
public class ExplanationArtifactBuilder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ExplanationArtifactBuilder.class);

    @Override
    public void process(Exchange exchange) {
        // ── Read from exchange properties ─────────────────────────────────────
        String decisionId   = exchange.getProperty("decisionId",   "unknown", String.class);
        String modelVersion = exchange.getProperty("modelVersion", "unknown", String.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = exchange.getProperty("evidence", Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> signals  = exchange.getProperty("signals",  Map.class);

        // ── Rationale comes from the exchange body (LLM narrative string) ─────
        Object bodyObj = exchange.getIn().getBody();
        String rationale = (bodyObj instanceof String s) ? s : (bodyObj != null ? bodyObj.toString() : "");

        // ── Build artifact ────────────────────────────────────────────────────
        ExplanationArtifact artifact = new ExplanationArtifact(
                decisionId,
                evidence  != null ? evidence : Map.of(),
                signals   != null ? signals  : Map.of(),
                rationale,
                modelVersion,
                null   // capturedAt auto-generated
        );

        exchange.getIn().setBody(artifact);

        log.debug("ExplanationArtifactBuilder: built artifact decisionId={} evidenceKeys={} signalKeys={}",
                decisionId,
                artifact.evidence().keySet(),
                artifact.signals().keySet());
    }
}