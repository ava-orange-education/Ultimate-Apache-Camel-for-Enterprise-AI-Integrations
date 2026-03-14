package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.ExplanationArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Assembles the narrative prompt by loading {@code prompts/explanation/audit-narrative.txt}
 * and filling in the structured evidence, signals, and decision variables.
 *
 * <p>Because the spec requires the LLM to receive <em>only structured JSON</em>
 * (not free-form text), both {@code evidence} and {@code signals} are serialised to
 * compact JSON strings before injection.
 *
 * <p>Reads from exchange body: {@link ExplanationArtifact} (set by the core
 * {@code ExplanationArtifactBuilder}).<br>
 * Writes to exchange body: the filled-in prompt string, ready for the LLM call.
 *
 * <h3>Template variables filled</h3>
 * <ul>
 *   <li>{@code {{evidence}}}  — JSON of artifact.evidence()</li>
 *   <li>{@code {{signals}}}   — JSON of artifact.signals()</li>
 *   <li>{@code {{decision}}}  — routingDecision from signals (or artifact decisionId)</li>
 * </ul>
 */
@Component
public class NarrativePromptAssembler implements Processor {

    private static final Logger log = LoggerFactory.getLogger(NarrativePromptAssembler.class);

    private static final String PROMPT_PATH = "prompts/explanation/audit-narrative.txt";

    private final com.aibook.ai.llm.PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public NarrativePromptAssembler(com.aibook.ai.llm.PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        ExplanationArtifact artifact = exchange.getIn().getBody(ExplanationArtifact.class);
        if (artifact == null) {
            throw new IllegalStateException(
                    "NarrativePromptAssembler: body must be an ExplanationArtifact");
        }

        // Serialise evidence and signals to compact JSON for the LLM
        String evidenceJson = toJson(artifact.evidence());
        String signalsJson  = toJson(artifact.signals());

        // Resolve the routing decision label for the {{decision}} placeholder
        Object decisionObj = artifact.signals().get("routingDecision");
        String decision = decisionObj != null ? decisionObj.toString() : artifact.decisionId();

        // Load template and fill variables
        String template = promptLoader.load(PROMPT_PATH);
        String prompt   = template
                .replace("{{evidence}}", evidenceJson)
                .replace("{{signals}}",  signalsJson)
                .replace("{{decision}}", decision);

        // Store the assembled prompt as body — the route forwards to LLM next
        exchange.getIn().setBody(prompt);
        // Preserve the artifact in a property so NarrativeResponseCapture can update it
        exchange.setProperty("explanationArtifact", artifact);

        log.info("NarrativePromptAssembler: prompt assembled for decisionId={} "
                 + "promptLen={}", artifact.decisionId(), prompt.length());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("NarrativePromptAssembler: JSON serialisation failed, using toString: {}",
                    e.getMessage());
            return map.toString();
        }
    }
}
