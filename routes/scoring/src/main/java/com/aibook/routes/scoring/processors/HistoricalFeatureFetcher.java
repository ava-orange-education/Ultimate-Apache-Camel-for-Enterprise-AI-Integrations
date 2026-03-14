package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage-2 (conditional) processor — simulates a database lookup for historical
 * scoring data associated with the entity.
 *
 * <p>In production this would query a database or cache. For the book demo it
 * generates plausible historical signals from the entity ID so the pipeline can
 * be exercised end-to-end without external dependencies.
 *
 * <p>Adds the following keys to the feature map on the exchange body:
 * <ul>
 *   <li>{@code prior_score}           — simulated previous risk score [0.0–1.0]</li>
 *   <li>{@code prior_score_count}     — number of historical score events</li>
 *   <li>{@code days_since_last_score} — recency of the last scoring event</li>
 *   <li>{@code score_trend}           — "IMPROVING", "STABLE", or "DEGRADING"</li>
 * </ul>
 *
 * <p>The enriched {@link ScoringRequest} replaces the exchange body so that
 * {@link FeatureMergeStrategy} is not needed here — this processor runs
 * <em>before</em> the split, within a single route step.
 */
@Component
public class HistoricalFeatureFetcher implements Processor {

    private static final Logger log = LoggerFactory.getLogger(HistoricalFeatureFetcher.class);

    @Override
    public void process(Exchange exchange) {
        ScoringRequest req = exchange.getIn().getBody(ScoringRequest.class);
        if (req == null) {
            log.warn("HistoricalFeatureFetcher: body is null, skipping historical enrichment");
            return;
        }

        // ── Simulate historical lookup based on entityId ──────────────────────
        Map<String, Object> enriched = new HashMap<>(req.features());

        // Deterministic simulation from entity hash (reproducible in tests)
        int entityHash      = Math.abs(req.entityId().hashCode());
        double priorScore   = (entityHash % 100) / 100.0;
        int priorCount      = (entityHash % 50) + 1;
        int daysSinceLast   = (entityHash % 90) + 1;
        String trend = priorScore > 0.6 ? "IMPROVING"
                     : priorScore < 0.4 ? "DEGRADING"
                     : "STABLE";

        enriched.put("prior_score",           priorScore);
        enriched.put("prior_score_count",     priorCount);
        enriched.put("days_since_last_score", daysSinceLast);
        enriched.put("score_trend",           trend);

        ScoringRequest enrichedReq = new ScoringRequest(
                req.requestId(), req.entityId(), req.entityType(), req.scoringProfile(),
                enriched, req.requestTime());

        exchange.getIn().setBody(enrichedReq);

        log.info("HistoricalFeatureFetcher: entityId={} priorScore={} trend={} priorCount={}",
                req.entityId(), priorScore, trend, priorCount);
    }
}
