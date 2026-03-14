package com.aibook.routes.explanation;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.explanation.processors.FeedbackReintegrator;
import com.aibook.routes.explanation.processors.ReviewDecisionProcessor;
import com.aibook.routes.explanation.processors.ReviewSimulator;
import com.aibook.routes.explanation.processors.ReviewTaskBuilder;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Human review route — manages the full human-in-the-loop lifecycle:
 * task submission, async queue processing, reviewer simulation, and feedback reintegration.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:submitHumanReview
 *     → reviewTaskBuilder              (builds HumanReviewTask from exchange state)
 *     → seda:humanReviewQueue          (async dispatch — non-blocking)
 *     → log "task submitted"
 *
 *   seda:humanReviewQueue              (background consumer)
 *     → reviewSimulator                (auto-approves/rejects based on priority)
 *     → direct:processFeedback
 *
 *   direct:processFeedback
 *     → feedbackReintegrator           (builds feedback + regression case document)
 *     → marshal().json()
 *     → multicast()
 *           → file:output/feedback/{taskId}-feedback.json
 *           → file:datasets/golden/regression_cases/{taskId}-regression.json
 *
 *   REST GET  /api/review/queue         → direct:listReviewQueue
 *   REST POST /api/review/decision/{taskId} → direct:processReviewDecision
 * </pre>
 */
@Component
public class HumanReviewRoute extends RouteBuilder {

    @Value("${aibook.feedback.output-dir:${java.io.tmpdir}/aibook/feedback}")
    private String feedbackOutputDir;

    @Value("${aibook.feedback.regression-dir:datasets/golden/regression_cases}")
    private String regressionDir;

    private final ReviewTaskBuilder        reviewTaskBuilder;
    private final ReviewSimulator          reviewSimulator;
    private final FeedbackReintegrator     feedbackReintegrator;
    private final ReviewDecisionProcessor  reviewDecisionProcessor;
    private final AiErrorHandler           aiErrorHandler;

    public HumanReviewRoute(ReviewTaskBuilder reviewTaskBuilder,
                            ReviewSimulator reviewSimulator,
                            FeedbackReintegrator feedbackReintegrator,
                            ReviewDecisionProcessor reviewDecisionProcessor,
                            AiErrorHandler aiErrorHandler) {
        this.reviewTaskBuilder       = reviewTaskBuilder;
        this.reviewSimulator         = reviewSimulator;
        this.feedbackReintegrator    = feedbackReintegrator;
        this.reviewDecisionProcessor = reviewDecisionProcessor;
        this.aiErrorHandler          = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "HumanReviewRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST endpoints ────────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/review")
                .description("Human review queue management endpoints")

                .get("/queue")
                    .description("List pending tasks in the human review queue")
                    .produces("application/json")
                    .to("direct:listReviewQueue")

                .post("/decision/{taskId}")
                    .description("Submit a reviewer decision for a queued task")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:processReviewDecision");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:submitHumanReview
        // Entry point — shared with scoring and graph modules.
        // Builds HumanReviewTask and dispatches async to seda queue.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:submitHumanReview")
                .routeId("human-review-submit")
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: submitting task for decisionId=${exchangeProperty.decisionId}")

                // Build the task from whatever is in the exchange (ScoringResult, HumanReviewTask,
                // or ExplanationArtifact) — processor is tolerant of all three
                .process(reviewTaskBuilder)
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: task built taskId=${header.reviewTaskId} "
                     + "priority=${header.priority}")

                // Dispatch to the async SEDA queue (non-blocking)
                .to("seda:humanReviewQueue?waitForTaskToComplete=Never")
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: HumanReviewTask submitted: taskId=${header.reviewTaskId}");

        // ═════════════════════════════════════════════════════════════════════
        // Route: seda:humanReviewQueue
        // Background consumer — simulates reviewer for demo/testing.
        // In production, replace reviewSimulator with actual human decision endpoint.
        // ═════════════════════════════════════════════════════════════════════
        from("seda:humanReviewQueue?concurrentConsumers=1")
                .routeId("human-review-queue-consumer")
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: processing queued task ${body.taskId} "
                     + "priority=${body.priority}")

                // Simulate reviewer decision (APPROVED/REJECTED based on priority)
                .process(reviewSimulator)
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: review outcome=${header.reviewOutcome} "
                     + "for taskId=${body.taskId}")

                .to("direct:processFeedback");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:processFeedback
        // Reintegrates review outcome into feedback and regression datasets.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:processFeedback")
                .routeId("human-review-feedback")
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: reintegrating feedback taskId=${body.taskId} "
                     + "outcome=${header.reviewOutcome}")

                // Build feedback + regression case document
                .process(feedbackReintegrator)

                // Marshal to JSON
                .marshal().json()

                // Multicast: feedback file + regression case
                .multicast()
                    .parallelProcessing(false)
                    .to("direct:feedbackFileWrite")
                    .to("direct:regressionCaseWrite")
                .end()

                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: feedback persisted for taskId=${header.feedbackTaskId}");

        // ── Feedback file write ───────────────────────────────────────────────
        from("direct:feedbackFileWrite")
                .routeId("feedback-file-write")
                .toD("file:" + feedbackOutputDir
                        + "?fileName=${header.feedbackTaskId}-feedback.json"
                        + "&fileExist=Override&autoCreate=true");

        // ── Regression case write ─────────────────────────────────────────────
        from("direct:regressionCaseWrite")
                .routeId("regression-case-write")
                .toD("file:" + regressionDir
                        + "?fileName=${header.feedbackTaskId}-regression.json"
                        + "&fileExist=Override&autoCreate=true");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:listReviewQueue
        // Returns a simple status document (in production, query a task store).
        // ═════════════════════════════════════════════════════════════════════
        from("direct:listReviewQueue")
                .routeId("human-review-list")
                .process(exchange -> {
                    // In a production implementation, this would query a task store or database.
                    // Here we return queue size information via SEDA component.
                    exchange.getIn().setBody(
                            "{\"queue\": \"seda:humanReviewQueue\", "
                            + "\"status\": \"active\", "
                            + "\"note\": \"Query task store for pending items in production\"}");
                    exchange.getIn().setHeader("Content-Type", "application/json");
                });

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:processReviewDecision
        // Accepts explicit reviewer decision via REST API.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:processReviewDecision")
                .routeId("human-review-decision")
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: processing review decision for taskId=${header.taskId}")

                // Deserialise JSON body to Map {outcome, notes, reviewerId}
                .unmarshal().json(java.util.Map.class)

                // Validate and extract decision; restore task body
                .process(reviewDecisionProcessor)
                .log(LoggingLevel.INFO,
                     "HumanReviewRoute: decision received taskId=${body.taskId} "
                     + "outcome=${header.reviewOutcome}")

                .to("direct:processFeedback");
    }
}