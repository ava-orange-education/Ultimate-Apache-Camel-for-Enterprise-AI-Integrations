package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.AggregationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregation strategy that merges the enriched {@link ScoringRequest} feature maps
 * returned by a Camel {@code enrich()} call into the original exchange.
 *
 * <p>Used by {@link com.aibook.routes.scoring.FeatureAssemblyRoute} in the
 * {@code enrich("direct:fetchHistoricalFeatures", featureMergeStrategy)} and
 * {@code enrich("direct:fetchContextualFeatures", featureMergeStrategy)} steps.
 *
 * <p>Merge semantics:
 * <ol>
 *   <li>The <em>original</em> exchange body (a {@link ScoringRequest}) provides the base
 *       feature map.</li>
 *   <li>The <em>resource</em> exchange body (also a {@link ScoringRequest}) provides
 *       enrichment features.</li>
 *   <li>Resource features are merged into the original — <strong>resource values win</strong>
 *       on key collision so that downstream enrichers can override stale upstream values.</li>
 *   <li>The merged result replaces the original exchange body as a new immutable
 *       {@link ScoringRequest} with all other fields preserved.</li>
 * </ol>
 *
 * <p>If the resource exchange is null or its body is not a {@link ScoringRequest},
 * the original exchange is returned unchanged.
 */
@Component
public class FeatureMergeStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(FeatureMergeStrategy.class);

    @Override
    public Exchange aggregate(Exchange original, Exchange resource) {
        if (original == null) return resource;
        if (resource == null) return original;

        ScoringRequest originalReq  = original.getIn().getBody(ScoringRequest.class);
        ScoringRequest resourceReq  = resource.getIn().getBody(ScoringRequest.class);

        if (originalReq == null) {
            log.warn("FeatureMergeStrategy: original body is not a ScoringRequest — returning original");
            return original;
        }
        if (resourceReq == null) {
            log.warn("FeatureMergeStrategy: resource body is not a ScoringRequest — returning original");
            return original;
        }

        // Merge: base = original features; overlay = resource features
        Map<String, Object> merged = new HashMap<>(originalReq.features());
        merged.putAll(resourceReq.features());   // resource wins on collision

        ScoringRequest mergedReq = new ScoringRequest(
                originalReq.requestId(),
                originalReq.entityId(),
                originalReq.entityType(),
                originalReq.scoringProfile(),
                merged,
                originalReq.requestTime()
        );

        original.getIn().setBody(mergedReq);
        original.getIn().setHeader("featureCount", merged.size());

        log.debug("FeatureMergeStrategy: merged {} original + {} resource = {} total features",
                originalReq.features().size(), resourceReq.features().size(), merged.size());

        return original;
    }
}
