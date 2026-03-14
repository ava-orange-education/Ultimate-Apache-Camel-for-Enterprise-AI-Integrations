package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.ExplanationArtifact;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures the LLM narrative response and attaches it to the
 * {@link ExplanationArtifact} by creating a new immutable record with the
 * {@code rationale} field populated.
 *
 * <p>The narrative exchange flow is:
 * <pre>
 *   NarrativePromptAssembler  → body = prompt String
 *   LLM call (direct or Camel endpoint)
 *   NarrativeResponseCapture  → body = ExplanationArtifact (with rationale)
 * </pre>
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: LLM response text (String)</li>
 *   <li>Exchange property {@code explanationArtifact}: original {@link ExplanationArtifact}
 *       stashed by {@link NarrativePromptAssembler}</li>
 * </ul>
 *
 * <p>Writes body: updated {@link ExplanationArtifact} with {@code rationale} populated.
 */
@Component
public class NarrativeResponseCapture implements Processor {

    private static final Logger log = LoggerFactory.getLogger(NarrativeResponseCapture.class);

    /** Maximum characters to retain from the LLM response. */
    private static final int MAX_RATIONALE_CHARS = 2_000;

    @Override
    public void process(Exchange exchange) {
        // Retrieve the original artifact stashed before the LLM call
        ExplanationArtifact original = exchange.getProperty(
                "explanationArtifact", ExplanationArtifact.class);

        if (original == null) {
            // Fallback: try to get from body if prompt wasn't assembled separately
            Object body = exchange.getIn().getBody();
            if (body instanceof ExplanationArtifact ea) {
                log.warn("NarrativeResponseCapture: no stashed artifact — body already is artifact, "
                        + "skipping rationale update");
                return;
            }
            log.warn("NarrativeResponseCapture: no stashed explanationArtifact found, "
                    + "creating placeholder");
            original = new ExplanationArtifact(
                    exchange.getProperty("decisionId", "unknown", String.class),
                    java.util.Map.of(), java.util.Map.of(), "", "unknown", null);
        }

        // Read LLM narrative from body
        Object bodyObj  = exchange.getIn().getBody();
        String narrative = (bodyObj instanceof String s) ? s.strip()
                         : bodyObj != null ? bodyObj.toString().strip() : "";

        // Truncate to avoid oversized audit records
        if (narrative.length() > MAX_RATIONALE_CHARS) {
            narrative = narrative.substring(0, MAX_RATIONALE_CHARS) + "…";
        }

        // Build updated artifact with rationale attached
        ExplanationArtifact updated = new ExplanationArtifact(
                original.decisionId(),
                original.evidence(),
                original.signals(),
                narrative,
                original.modelVersion(),
                original.capturedAt()
        );

        exchange.getIn().setBody(updated);
        // Keep artifact available for downstream processors too
        exchange.setProperty("explanationArtifact", updated);

        log.info("NarrativeResponseCapture: rationale captured decisionId={} chars={}",
                updated.decisionId(), narrative.length());
    }
}
