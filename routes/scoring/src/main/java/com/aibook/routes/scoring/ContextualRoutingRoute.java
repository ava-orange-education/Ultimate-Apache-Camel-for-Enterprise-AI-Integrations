package com.aibook.routes.scoring;

import com.aibook.core.dto.ScoringResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.scoring.processors.HumanReviewTaskBuilder;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Contextual routing route — dispatches {@link ScoringResult} to APPROVE, REVIEW,
 * or ESCALATE flows based on the {@code routingDecision} header set by
 * {@link com.aibook.routes.scoring.processors.ConfidenceEvaluator}.
 *
 * <h3>Route overview</h3>
 * <pre>
 *   direct:contextualRouting
 *     → auditArtifactCollector
 *     → choice()
 *         APPROVE  → direct:approveFlow
 *         REVIEW   → direct:reviewFlow
 *         default  → direct:escalateFlow
 *
 *   direct:approveFlow
 *     → log AUTO-APPROVED
 *     → marshal JSON
 *     → file:output/scoring/approved/{requestId}.json
 *
 *   direct:reviewFlow
 *     → direct:createHumanReviewTask
 *
 *   direct:escalateFlow
 *     → log ESCALATED
 *     → direct:deadLetter
 *
 *   direct:createHumanReviewTask
 *     → humanReviewTaskBuilder  (builds HumanReviewTask DTO)
 *     → direct:submitHumanReview  (bridge to explanation module / Chapter 13)
 * </pre>
 */
@Component
public class ContextualRoutingRoute extends RouteBuilder {

    @Value("${aibook.scoring.output-dir:${java.io.tmpdir}/aibook/scoring}")
    private String outputDir;

    private final AuditArtifactCollector auditCollector;
    private final HumanReviewTaskBuilder humanReviewTaskBuilder;
    private final AiErrorHandler         aiErrorHandler;

    public ContextualRoutingRoute(AuditArtifactCollector auditCollector,
                                  HumanReviewTaskBuilder humanReviewTaskBuilder,
                                  AiErrorHandler aiErrorHandler) {
        this.auditCollector       = auditCollector;
        this.humanReviewTaskBuilder = humanReviewTaskBuilder;
        this.aiErrorHandler        = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ContextualRoutingRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:contextualRouting
        // Audit then branch on routingDecision header.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:contextualRouting")
                .routeId("contextual-routing")
                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: routing decision=${header.routingDecision} "
                     + "score=${header.score} requestId=${header.requestId}")

                // Capture audit record (reads stage/decisionId/modelVersion from exchange properties)
                .setProperty("stage",        constant("contextual-routing"))
                .process(auditCollector)

                .choice()
                    .when(header("routingDecision").isEqualTo("APPROVE"))
                        .to("direct:approveFlow")
                    .when(header("routingDecision").isEqualTo("REVIEW"))
                        .to("direct:reviewFlow")
                    .otherwise()
                        .to("direct:escalateFlow")
                .end();

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:approveFlow
        // Auto-approved: marshal to JSON and write to the approved output dir.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:approveFlow")
                .routeId("scoring-approve-flow")
                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: AUTO-APPROVED entityId=${header.entityId} "
                     + "score=${header.score} requestId=${header.requestId}")

                // Marshal ScoringResult to JSON
                .marshal().json()

                // Persist to approved output directory
                .to("file:" + outputDir + "/approved?fileName=${header.requestId}.json")

                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: approved result saved for requestId=${header.requestId}");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:reviewFlow
        // Sends the result to the human review task builder.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:reviewFlow")
                .routeId("scoring-review-flow")
                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: REVIEW required for entityId=${header.entityId} "
                     + "confidence=${header.confidence} requestId=${header.requestId}")
                .to("direct:createHumanReviewTask");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:escalateFlow
        // High-risk or very low confidence — log and send to dead-letter for
        // manual investigation capture.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:escalateFlow")
                .routeId("scoring-escalate-flow")
                .log(LoggingLevel.WARN,
                     "ContextualRoutingRoute: ESCALATED entityId=${header.entityId} "
                     + "score=${header.score} confidence=${header.confidence}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:createHumanReviewTask
        // Bridge to explanation module (Chapter 13). Builds HumanReviewTask DTO
        // and forwards to the shared human-review queue.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:createHumanReviewTask")
                .routeId("scoring-create-review-task")
                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: creating human review task for "
                     + "requestId=${header.requestId}")

                // Build the HumanReviewTask from the ScoringResult body
                .process(humanReviewTaskBuilder)
                .log(LoggingLevel.INFO,
                     "ContextualRoutingRoute: review task created taskId=${header.reviewTaskId} "
                     + "priority=${header.priority}")

                // Forward to the shared human-review endpoint (defined in explanation module)
                .to("direct:submitHumanReview");
    }
}