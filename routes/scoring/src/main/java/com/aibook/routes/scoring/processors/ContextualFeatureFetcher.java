package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage-3 processor — fetches contextual features from the vector store or graph.
 *
 * <p>For the book demo this generates lightweight contextual signals (peer
 * comparison, sector risk, time-of-day risk window) without requiring a live
 * Qdrant or Neo4j connection, so the pipeline can run in unit tests and demos
 * without external dependencies.
 *
 * <p>In production, replace the body of this processor with a call to
 * {@link com.aibook.ai.vector.VectorSearchService} or
 * {@link com.aibook.ai.graph.GraphTraversalService}.
 *
 * <p>Adds the following keys to the feature map:
 * <ul>
 *   <li>{@code sector_risk_index}     — industry/sector baseline risk score [0.0–1.0]</li>
 *   <li>{@code peer_avg_score}        — average score of similar entities</li>
 *   <li>{@code high_risk_time_window} — {@code true} if request is in a risk window</li>
 *   <li>{@code context_source}        — which context source was used</li>
 * </ul>
 */
@Component
public class ContextualFeatureFetcher implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ContextualFeatureFetcher.class);

    @Value("${aibook.scoring.context-source:mock}")
    private String contextSource;

    @Override
    public void process(Exchange exchange) {
        ScoringRequest req = exchange.getIn().getBody(ScoringRequest.class);
        if (req == null) {
            log.warn("ContextualFeatureFetcher: body is null, skipping contextual enrichment");
            return;
        }

        Map<String, Object> enriched = new HashMap<>(req.features());

        // ── Simulate contextual lookup ────────────────────────────────────────
        int entityHash       = Math.abs(req.entityId().hashCode());
        double sectorRisk    = ((entityHash % 30) + 10) / 100.0;     // 0.10 – 0.40
        double peerAvgScore  = ((entityHash % 40) + 50) / 100.0;     // 0.50 – 0.90

        // High-risk time window: afternoon hours UTC (12:00 – 18:00) treated as elevated
        int hourUtc          = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).getHour();
        boolean highRiskTime = hourUtc >= 12 && hourUtc <= 18;

        enriched.put("sector_risk_index",     sectorRisk);
        enriched.put("peer_avg_score",        peerAvgScore);
        enriched.put("high_risk_time_window", highRiskTime);
        enriched.put("context_source",        contextSource);

        ScoringRequest enrichedReq = new ScoringRequest(
                req.requestId(), req.entityId(), req.entityType(), req.scoringProfile(),
                enriched, req.requestTime());

        exchange.getIn().setBody(enrichedReq);

        // Update header with new total feature count
        exchange.getIn().setHeader("featureCount", enriched.size());

        log.info("ContextualFeatureFetcher: entityId={} sectorRisk={} peerAvg={} features={}",
                req.entityId(), sectorRisk, peerAvgScore, enriched.size());
    }
}
