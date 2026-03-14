package com.aibook.routes.explanation;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.ExplanationArtifactBuilder;
import com.aibook.routes.explanation.processors.ExplanationEvidenceCollector;
import com.aibook.routes.explanation.processors.NarrativePromptAssembler;
import com.aibook.routes.explanation.processors.NarrativeResponseCapture;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Explanation artifact route — constructs an {@link com.aibook.core.dto.ExplanationArtifact}
 * for every AI pipeline decision and generates an LLM audit narrative.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:buildExplanation
 *     → explanationEvidenceCollector  (assembles evidence + signals from exchange state)
 *     → explanationArtifactBuilder    (core: builds ExplanationArtifact, body = artifact)
 *     → direct:generateNarrative
 *
 *   direct:generateNarrative
 *     → narrativePromptAssembler      (loads audit-narrative.txt, fills vars,
 *                                      stashes artifact, body = prompt String)
 *     → llmGateway.chat()             (inline — keeps control without langchain4j endpoint)
 *     → narrativeResponseCapture      (body = ExplanationArtifact with rationale)
 *     → direct:storeAudit
 * </pre>
 *
 * <h3>LLM interaction</h3>
 * The prompt is assembled with structured JSON for {@code evidence} and {@code signals}
 * — the LLM never receives free-form text, ensuring auditable consistency.
 *
 * <h3>Exchange state required</h3>
 * <ul>
 *   <li>Body: {@link com.aibook.core.dto.ScoringResult} (or header {@code ScoringResult})</li>
 *   <li>Property {@code auditTrail}: {@code List<AuditRecord>}</li>
 *   <li>Property {@code decisionId}: String</li>
 * </ul>
 */
@Component
public class ExplanationArtifactRoute extends RouteBuilder {

    private final ExplanationEvidenceCollector evidenceCollector;
    private final ExplanationArtifactBuilder   artifactBuilder;
    private final NarrativePromptAssembler     promptAssembler;
    private final NarrativeResponseCapture     responseCapture;
    private final LlmGateway                   llmGateway;
    private final AiErrorHandler               aiErrorHandler;

    public ExplanationArtifactRoute(ExplanationEvidenceCollector evidenceCollector,
                                    ExplanationArtifactBuilder artifactBuilder,
                                    NarrativePromptAssembler promptAssembler,
                                    NarrativeResponseCapture responseCapture,
                                    LlmGateway llmGateway,
                                    AiErrorHandler aiErrorHandler) {
        this.evidenceCollector = evidenceCollector;
        this.artifactBuilder   = artifactBuilder;
        this.promptAssembler   = promptAssembler;
        this.responseCapture   = responseCapture;
        this.llmGateway        = llmGateway;
        this.aiErrorHandler    = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(AiGatewayException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ExplanationArtifactRoute: LLM narrative generation failed "
                     + "[decisionId=${exchangeProperty.decisionId}]: ${exception.message}")
                // Even on LLM failure, attempt to store audit with empty rationale
                .to("direct:storeAudit");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ExplanationArtifactRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:buildExplanation
        // Input:  ScoringResult body + auditTrail exchange property
        // Output: ExplanationArtifact body → direct:generateNarrative
        // ═════════════════════════════════════════════════════════════════════
        from("direct:buildExplanation")
                .routeId("explanation-artifact")
                .log(LoggingLevel.INFO,
                     "ExplanationArtifactRoute: building explanation for "
                     + "decisionId=${exchangeProperty.decisionId}")

                // Stage 1: Assemble evidence + signals from exchange state
                .process(evidenceCollector)
                .log(LoggingLevel.DEBUG,
                     "ExplanationArtifactRoute: evidence assembled for "
                     + "decisionId=${exchangeProperty.decisionId}")

                // Stage 2: Build ExplanationArtifact (rationale = empty at this point)
                .setBody(constant(""))    // rationale will be filled by LLM next
                .process(artifactBuilder)
                .log(LoggingLevel.INFO,
                     "ExplanationArtifactRoute: artifact built for "
                     + "decisionId=${exchangeProperty.decisionId}")

                .to("direct:generateNarrative");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:generateNarrative
        // Input:  ExplanationArtifact body
        // Output: ExplanationArtifact body with rationale populated → direct:storeAudit
        // ═════════════════════════════════════════════════════════════════════
        from("direct:generateNarrative")
                .routeId("explanation-narrative")
                .log(LoggingLevel.INFO,
                     "ExplanationArtifactRoute: generating narrative for "
                     + "decisionId=${exchangeProperty.decisionId}")

                // Assemble structured JSON prompt; stash artifact; body = prompt String
                .process(promptAssembler)

                // Call LLM inline — receives the filled prompt as the user message
                .process(exchange -> {
                    String prompt = exchange.getIn().getBody(String.class);
                    try {
                        String narrative = llmGateway.chat(
                                "You are an AI audit assistant. Follow the instructions exactly.",
                                prompt);
                        exchange.getIn().setBody(narrative);
                    } catch (AiGatewayException e) {
                        log.warn("ExplanationArtifactRoute: LLM failed, using fallback narrative: {}",
                                e.getMessage());
                        exchange.getIn().setBody(
                                "[Narrative unavailable — LLM call failed: " + e.getMessage() + "]");
                    }
                })

                // Attach narrative to ExplanationArtifact; body = updated artifact
                .process(responseCapture)
                .log(LoggingLevel.INFO,
                     "ExplanationArtifactRoute: narrative captured for "
                     + "decisionId=${exchangeProperty.decisionId}")

                .to("direct:storeAudit");
    }
}