package com.aibook.routes.graph.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides whether graph traversal enrichment is needed for the current exchange.
 *
 * <p>Graph enrichment is triggered when:
 * <ol>
 *   <li>The {@code graphEnrichmentNeeded} header is already {@code true} (caller override)</li>
 *   <li>The {@code confidence} header falls in the MEDIUM band (0.5–0.8) — borderline cases
 *       benefit most from graph context</li>
 *   <li>The {@code score} header is in the mid-risk range (0.3–0.65) — not clear enough
 *       to auto-approve without additional graph signals</li>
 *   <li>The {@code riskFlagCount} feature header is &gt; 0</li>
 * </ol>
 *
 * <p>Reads headers: {@code confidence}, {@code score}, {@code riskFlagCount},
 *   {@code entityId}, {@code requestId}.<br>
 * Writes headers: {@code graphEnrichmentNeeded} (boolean), {@code entityId} (echoed).
 */
@Component
public class GraphEnrichmentDecider implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphEnrichmentDecider.class);

    /** Lower bound of the medium confidence band (inclusive). */
    static final double CONFIDENCE_MEDIUM_LOW  = 0.5;
    /** Upper bound of the medium confidence band (exclusive). */
    static final double CONFIDENCE_MEDIUM_HIGH = 0.8;
    /** Score above which we are still uncertain enough to need graph context. */
    static final double SCORE_MID_LOW   = 0.3;
    static final double SCORE_MID_HIGH  = 0.65;

    @Override
    public void process(Exchange exchange) {
        // Caller may force enrichment
        Boolean forced = exchange.getIn().getHeader("graphEnrichmentNeeded", Boolean.class);
        if (Boolean.TRUE.equals(forced)) {
            log.info("GraphEnrichmentDecider: enrichment forced by caller header");
            ensureEntityId(exchange);
            return;
        }

        double confidence    = doubleHeader(exchange, "confidence", -1.0);
        double score         = doubleHeader(exchange, "score",       -1.0);
        int    riskFlagCount = intHeader(exchange, "riskFlagCount",  0);

        boolean confidenceMedium = confidence >= CONFIDENCE_MEDIUM_LOW
                                && confidence <  CONFIDENCE_MEDIUM_HIGH;
        boolean scoreMidRange    = score >= SCORE_MID_LOW && score <= SCORE_MID_HIGH;
        boolean hasRiskFlags     = riskFlagCount > 0;

        boolean needed = confidenceMedium || scoreMidRange || hasRiskFlags;

        exchange.getIn().setHeader("graphEnrichmentNeeded", needed);
        ensureEntityId(exchange);

        log.info("GraphEnrichmentDecider: entityId={} confidence={} score={} riskFlags={} → needed={}",
                exchange.getIn().getHeader("entityId"), confidence, score, riskFlagCount, needed);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void ensureEntityId(Exchange exchange) {
        // entityId may come from the body or from a header set upstream
        if (exchange.getIn().getHeader("entityId") == null) {
            Object body = exchange.getIn().getBody();
            if (body instanceof com.aibook.core.dto.ScoringResult sr) {
                exchange.getIn().setHeader("entityId", sr.entityId());
            } else if (body instanceof com.aibook.core.dto.ScoringRequest req) {
                exchange.getIn().setHeader("entityId", req.entityId());
            }
        }
    }

    private double doubleHeader(Exchange ex, String name, double fallback) {
        Object val = ex.getIn().getHeader(name);
        if (val == null) return fallback;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int intHeader(Exchange ex, String name, int fallback) {
        Object val = ex.getIn().getHeader(name);
        if (val == null) return fallback;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
