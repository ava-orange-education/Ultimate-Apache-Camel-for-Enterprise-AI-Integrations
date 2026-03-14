package com.aibook.routes.scoring;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.scoring.processors.ContextualFeatureFetcher;
import com.aibook.routes.scoring.processors.FeatureMergeStrategy;
import com.aibook.routes.scoring.processors.HistoricalFeatureFetcher;
import com.aibook.routes.scoring.processors.ImmediateFeatureExtractor;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

/**
 * Feature assembly route — performs staged feature enrichment of a
 * {@link com.aibook.core.dto.ScoringRequest} before forwarding to scoring.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   REST POST /api/score
 *     → direct:assembleScoringFeatures
 *         Stage 1: immediateFeatureExtractor  (validate + promote payload features)
 *         Stage 2: (conditional) enrich("direct:fetchHistoricalFeatures", featureMergeStrategy)
 *         Stage 3: enrich("direct:fetchContextualFeatures",  featureMergeStrategy)
 *         → direct:scoreRequest
 *
 *   direct:fetchHistoricalFeatures
 *     → historicalFeatureFetcher  (simulates prior-score DB lookup)
 *
 *   direct:fetchContextualFeatures
 *     → contextualFeatureFetcher  (sector risk, peer avg, time-window signals)
 * </pre>
 *
 * <h3>Headers produced</h3>
 * <ul>
 *   <li>{@code featureCount}   — total feature count after all enrichment stages</li>
 *   <li>{@code needsHistory}   — whether historical enrichment was applied</li>
 *   <li>{@code entityId}       — from the request DTO</li>
 *   <li>{@code requestId}      — from the request DTO</li>
 *   <li>{@code validationFailed} / {@code validationFailureReason}</li>
 * </ul>
 */
@Component
public class FeatureAssemblyRoute extends RouteBuilder {

    private final ImmediateFeatureExtractor immediateFeatureExtractor;
    private final HistoricalFeatureFetcher  historicalFeatureFetcher;
    private final ContextualFeatureFetcher  contextualFeatureFetcher;
    private final FeatureMergeStrategy      featureMergeStrategy;
    private final AiErrorHandler            aiErrorHandler;

    public FeatureAssemblyRoute(ImmediateFeatureExtractor immediateFeatureExtractor,
                                HistoricalFeatureFetcher historicalFeatureFetcher,
                                ContextualFeatureFetcher contextualFeatureFetcher,
                                FeatureMergeStrategy featureMergeStrategy,
                                AiErrorHandler aiErrorHandler) {
        this.immediateFeatureExtractor = immediateFeatureExtractor;
        this.historicalFeatureFetcher  = historicalFeatureFetcher;
        this.contextualFeatureFetcher  = contextualFeatureFetcher;
        this.featureMergeStrategy      = featureMergeStrategy;
        this.aiErrorHandler            = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalArgumentException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "FeatureAssemblyRoute: invalid input [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "FeatureAssemblyRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST entry point ──────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/score")
                .description("Real-time scoring pipeline entry point")
                .post()
                    .description("Submit a ScoringRequest JSON body for risk scoring")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:assembleScoringFeatures");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:assembleScoringFeatures
        // Input:  ScoringRequest JSON body
        // Output: enriched ScoringRequest → direct:scoreRequest
        // ═════════════════════════════════════════════════════════════════════
        from("direct:assembleScoringFeatures")
                .routeId("feature-assembly")
                .log(LoggingLevel.INFO,
                     "FeatureAssemblyRoute: starting feature assembly [exchangeId=${exchangeId}]")

                // Deserialize JSON body into ScoringRequest DTO
                .unmarshal().json(com.aibook.core.dto.ScoringRequest.class)

                // ── Stage 1: Immediate features ───────────────────────────────
                .process(immediateFeatureExtractor)
                .log(LoggingLevel.INFO,
                     "FeatureAssemblyRoute: Stage 1 complete — "
                     + "${header.featureCount} features, needsHistory=${header.needsHistory}")

                // ── Stage 2: Historical features (conditional) ────────────────
                .choice()
                    .when(header("needsHistory").isEqualTo(true))
                        .log(LoggingLevel.DEBUG,
                             "FeatureAssemblyRoute: enriching with historical features")
                        .enrich("direct:fetchHistoricalFeatures", featureMergeStrategy)
                        .log(LoggingLevel.DEBUG,
                             "FeatureAssemblyRoute: historical enrichment done, "
                             + "featureCount=${header.featureCount}")
                .end()

                // ── Stage 3: Contextual features (always) ─────────────────────
                .enrich("direct:fetchContextualFeatures", featureMergeStrategy)
                .log(LoggingLevel.INFO,
                     "FeatureAssemblyRoute: all stages complete — "
                     + "${header.featureCount} total features for requestId=${header.requestId}")

                .to("direct:scoreRequest");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:fetchHistoricalFeatures
        // Returns enriched ScoringRequest with prior-score fields added.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:fetchHistoricalFeatures")
                .routeId("fetch-historical-features")
                .log(LoggingLevel.DEBUG,
                     "FeatureAssemblyRoute: fetching historical features for "
                     + "entityId=${body.entityId}")
                .process(historicalFeatureFetcher);

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:fetchContextualFeatures
        // Returns enriched ScoringRequest with sector/peer/time-window signals.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:fetchContextualFeatures")
                .routeId("fetch-contextual-features")
                .log(LoggingLevel.DEBUG,
                     "FeatureAssemblyRoute: fetching contextual features for "
                     + "entityId=${body.entityId}")
                .process(contextualFeatureFetcher);
    }
}