package com.aibook.routes.scoring.processors;

import com.aibook.core.dto.ScoringRequest;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage-1 feature extractor — validates the request payload and promotes
 * immediate (request-time) features onto the exchange for downstream enrichment.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Reads the {@link ScoringRequest} from the exchange body.</li>
 *   <li>Validates that all required feature keys are present.</li>
 *   <li>Sets header {@code featureCount} with the number of features present.</li>
 *   <li>Sets header {@code needsHistory} based on a configurable flag or feature values.</li>
 *   <li>Sets exchange properties {@code decisionId} and {@code stage} for audit.</li>
 * </ol>
 *
 * <p>If required features are missing the exchange is flagged via header
 * {@code validationFailed=true} and {@code validationFailureReason}.
 */
@Component
public class ImmediateFeatureExtractor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ImmediateFeatureExtractor.class);

    /** Feature keys that must be present in every scoring request. */
    private static final Set<String> REQUIRED_KEYS = Set.of("entityId", "account_age_days");

    /** Feature key whose presence triggers historical enrichment. */
    private static final String HISTORY_TRIGGER_KEY = "needs_history";

    @Value("${aibook.scoring.always-fetch-history:false}")
    private boolean alwaysFetchHistory;

    @Override
    public void process(Exchange exchange) {
        ScoringRequest req = exchange.getIn().getBody(ScoringRequest.class);
        if (req == null) {
            throw new IllegalArgumentException(
                    "ImmediateFeatureExtractor: body must be a ScoringRequest record");
        }

        Map<String, Object> features = req.features();

        // ── Validate required keys ────────────────────────────────────────────
        List<String> missing = new ArrayList<>();
        for (String key : REQUIRED_KEYS) {
            // entityId is on the DTO itself, not in the features map
            if ("entityId".equals(key)) {
                if (req.entityId() == null || req.entityId().isBlank()) missing.add(key);
            } else if (!features.containsKey(key)) {
                missing.add(key);
            }
        }

        if (!missing.isEmpty()) {
            log.warn("ImmediateFeatureExtractor: missing required features {} for requestId={}",
                    missing, req.requestId());
            exchange.getIn().setHeader("validationFailed",        true);
            exchange.getIn().setHeader("validationFailureReason",
                    "missing required features: " + missing);
        } else {
            exchange.getIn().setHeader("validationFailed", false);
        }

        // ── Set feature metadata headers ──────────────────────────────────────
        exchange.getIn().setHeader("featureCount", features.size());
        exchange.getIn().setHeader("entityId",     req.entityId());
        exchange.getIn().setHeader("requestId",    req.requestId());

        // ── Determine if historical enrichment is needed ──────────────────────
        boolean needsHistory = alwaysFetchHistory
                || Boolean.TRUE.equals(features.get(HISTORY_TRIGGER_KEY))
                || features.containsKey("prior_score_required");
        exchange.getIn().setHeader("needsHistory", needsHistory);

        // ── Set audit exchange properties ─────────────────────────────────────
        exchange.setProperty("decisionId",   req.requestId());
        exchange.setProperty("stage",        "feature-assembly");
        exchange.setProperty("evidence",     features);

        log.info("ImmediateFeatureExtractor: requestId={} entityId={} featureCount={} needsHistory={}",
                req.requestId(), req.entityId(), features.size(), needsHistory);
    }
}
