package com.aibook.routes.scoring;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.scoring.processors.ConfidenceEvaluator;
import com.aibook.routes.scoring.processors.ModelScoringProcessor;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Scoring route — calls the LLM-based scoring model and evaluates confidence.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:scoreRequest
 *     → modelScoringProcessor   (calls LLM with score-explanation.txt prompt,
 *                                 falls back to rule-based if LLM unavailable)
 *     → confidenceEvaluator     (sets routingDecision header: APPROVE / REVIEW / ESCALATE)
 *     → direct:contextualRouting
 * </pre>
 *
 * <h3>Headers consumed</h3>
 * <ul>
 *   <li>{@code entityId}, {@code requestId}, {@code featureCount}</li>
 * </ul>
 *
 * <h3>Headers produced</h3>
 * <ul>
 *   <li>{@code score}            — double [0.0–1.0]</li>
 *   <li>{@code confidence}       — double [0.0–1.0]</li>
 *   <li>{@code modelVersion}     — model name string</li>
 *   <li>{@code routingDecision}  — "APPROVE", "REVIEW", or "ESCALATE"</li>
 *   <li>{@code confidenceBand}   — "HIGH", "MEDIUM", or "LOW"</li>
 * </ul>
 */
@Component
public class ScoringRoute extends RouteBuilder {

    private final ModelScoringProcessor modelScoringProcessor;
    private final ConfidenceEvaluator   confidenceEvaluator;
    private final AiErrorHandler        aiErrorHandler;

    public ScoringRoute(ModelScoringProcessor modelScoringProcessor,
                        ConfidenceEvaluator confidenceEvaluator,
                        AiErrorHandler aiErrorHandler) {
        this.modelScoringProcessor = modelScoringProcessor;
        this.confidenceEvaluator   = confidenceEvaluator;
        this.aiErrorHandler        = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(AiGatewayException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ScoringRoute: LLM call failed [requestId=${header.requestId}]: "
                     + "${exception.message}")
                // Let the fallback in ModelScoringProcessor handle this;
                // if we reach here the exception escaped the processor try-catch
                .to("direct:deadLetter");

        onException(IllegalArgumentException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ScoringRoute: invalid input [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "ScoringRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:scoreRequest
        // Input:  enriched ScoringRequest body from FeatureAssemblyRoute
        // Output: ScoringResult body → direct:contextualRouting
        // ═════════════════════════════════════════════════════════════════════
        from("direct:scoreRequest")
                .routeId("scoring-main")
                .log(LoggingLevel.INFO,
                     "ScoringRoute: scoring entityId=${header.entityId} "
                     + "featureCount=${header.featureCount}")

                // Call LLM scoring model (with rule-based fallback)
                // Circuit breaker guards against LLM unavailability
                .circuitBreaker()
                    .resilience4jConfiguration()
                        .slidingWindowSize(10)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(30000)
                        .end()
                    .process(modelScoringProcessor)
                .onFallback()
                    .process(exchange -> {
                        // Rule-based fallback score when LLM circuit is open
                        exchange.getIn().setHeader("score",            0.5);
                        exchange.getIn().setHeader("confidence",       0.3);
                        exchange.getIn().setHeader("modelVersion",     "fallback-rules");
                        exchange.getIn().setHeader("circuitBreakerFallback", true);
                    })
                .end()
                .log(LoggingLevel.INFO,
                     "ScoringRoute: raw score=${header.score} confidence=${header.confidence}")

                // Evaluate confidence and determine routing decision
                .process(confidenceEvaluator)
                .log(LoggingLevel.INFO,
                     "ScoringRoute: routingDecision=${header.routingDecision} "
                     + "band=${header.confidenceBand} for requestId=${header.requestId}")

                .to("direct:contextualRouting");
    }
}