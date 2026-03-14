package com.aibook.core.processors;

import com.aibook.core.dto.SummaryResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link SummaryResult} against quality gates:
 * <ol>
 *   <li>Body must be a non-null {@link SummaryResult}</li>
 *   <li>{@code summaryText} must not be blank</li>
 *   <li>{@code summaryText} must be at most 2 000 characters</li>
 * </ol>
 *
 * On success: no extra headers set; downstream can proceed normally.<br>
 * On failure: sets header {@code validationFailed=true} and
 * {@code validationFailureReason} with a description, then logs a warning.
 * The exchange body is left unchanged so the dead-letter route can inspect it.
 */
@Component
public class SummaryValidator implements Processor {

    private static final Logger log = LoggerFactory.getLogger(SummaryValidator.class);

    static final int MAX_SUMMARY_CHARS = 2_000;

    @Override
    public void process(Exchange exchange) {
        Object body = exchange.getIn().getBody();

        // ── Guard: body must be a SummaryResult ──────────────────────────────
        if (!(body instanceof SummaryResult result)) {
            fail(exchange,
                 "body_not_summary_result",
                 "Expected SummaryResult but got: " + (body == null ? "null" : body.getClass().getName()));
            return;
        }

        // ── Guard: summaryText not blank ──────────────────────────────────────
        if (result.summaryText() == null || result.summaryText().isBlank()) {
            fail(exchange, "blank_summary", "summaryText is null or blank");
            return;
        }

        // ── Guard: summaryText under 2 000 chars ──────────────────────────────
        if (result.summaryText().length() > MAX_SUMMARY_CHARS) {
            fail(exchange,
                 "summary_too_long",
                 "summaryText length " + result.summaryText().length()
                         + " exceeds limit of " + MAX_SUMMARY_CHARS);
            return;
        }

        // ── All checks passed ─────────────────────────────────────────────────
        log.debug("SummaryValidator: passed for sourceId={} len={}",
                result.sourceId(), result.summaryText().length());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void fail(Exchange exchange, String reason, String detail) {
        log.warn("SummaryValidator: validation failed [{}] — {} (exchangeId={})",
                reason, detail, exchange.getExchangeId());
        exchange.getIn().setHeader("validationFailed",        true);
        exchange.getIn().setHeader("validationFailureReason", reason);
        exchange.getIn().setHeader("validationFailureDetail", detail);
    }
}