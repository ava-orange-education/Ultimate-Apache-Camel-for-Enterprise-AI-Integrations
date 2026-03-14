package com.aibook.core.error;

import org.apache.camel.Exchange;
import org.apache.camel.builder.DeadLetterChannelBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Provides a pre-configured {@link DeadLetterChannelBuilder} factory method
 * and an {@code onExceptionOccurred} callback that enriches MDC before every
 * retry/failure log entry.
 *
 * <p>Policy:
 * <ul>
 *   <li>maximumRedeliveries = 3</li>
 *   <li>redeliveryDelay     = 1 000 ms</li>
 *   <li>backOffMultiplier   = 2.0  (exponential back-off)</li>
 *   <li>retryOn             = {@link RuntimeException} and {@link IOException}</li>
 *   <li>deadLetterUri       = {@code direct:deadLetter}</li>
 * </ul>
 *
 * <p>Usage inside any {@link RouteBuilder}:
 * <pre>{@code
 *   errorHandler(aiErrorHandler.build());
 * }</pre>
 */
@Component
public class AiErrorHandler extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(AiErrorHandler.class);

    // ── MDC key constants ─────────────────────────────────────────────────────
    public static final String MDC_DECISION_ID   = "decisionId";
    public static final String MDC_ROUTE_ID      = "routeId";
    public static final String MDC_EXCHANGE_ID   = "exchangeId";
    public static final String MDC_RETRY_ATTEMPT = "retryAttempt";

    // ── Policy constants (visible for tests) ──────────────────────────────────
    static final int    MAX_REDELIVERIES    = 3;
    static final long   REDELIVERY_DELAY_MS = 1_000L;
    static final double BACK_OFF_MULTIPLIER = 2.0;

    /**
     * Build a fully configured {@link DeadLetterChannelBuilder}.
     * Call this from every pipeline {@link RouteBuilder#configure()} method.
     */
    public DeadLetterChannelBuilder build() {
        DeadLetterChannelBuilder builder = deadLetterChannel("direct:deadLetter");
        builder.maximumRedeliveries(MAX_REDELIVERIES);
        builder.redeliveryDelay(REDELIVERY_DELAY_MS);
        builder.backOffMultiplier(BACK_OFF_MULTIPLIER);
        builder.useExponentialBackOff();
        builder.retryAttemptedLogLevel(org.apache.camel.LoggingLevel.WARN);
        builder.logExhausted(true);
        builder.logRetryAttempted(true);
        builder.logRetryStackTrace(false);
        builder.onRedelivery(this::onRedelivery);
        builder.onExceptionOccurred(this::onExceptionOccurred);
        return builder;
    }

    /**
     * Called before each redelivery attempt — injects MDC context for structured logging.
     */
    private void onRedelivery(Exchange exchange) {
        populateMdc(exchange);
        int attempt = exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER, 0, Integer.class);
        log.warn("Redelivery attempt {}/{} for route={} decisionId={}",
                attempt, MAX_REDELIVERIES,
                exchange.getFromRouteId(),
                exchange.getProperty("decisionId", "unknown", String.class));
        clearMdc();
    }

    /**
     * Called each time an exception occurs (before potential redelivery).
     */
    private void onExceptionOccurred(Exchange exchange) {
        populateMdc(exchange);
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        log.error("Exception in route={} decisionId={} class={} message={}",
                exchange.getFromRouteId(),
                exchange.getProperty("decisionId", "unknown", String.class),
                cause != null ? cause.getClass().getSimpleName() : "unknown",
                cause != null ? cause.getMessage()               : "none");
        clearMdc();
    }

    // ── RouteBuilder configure ────────────────────────────────────────────────

    /**
     * No global routes defined here — the dead-letter sink is in
     * {@link DeadLetterSupport}. Individual routes call {@link #build()} and
     * declare their own {@code onException} clauses.
     */
    @Override
    public void configure() {
        // intentionally empty — all configuration via build() and per-route onException
    }

    // ── MDC helpers ───────────────────────────────────────────────────────────

    private void populateMdc(Exchange exchange) {
        MDC.put(MDC_DECISION_ID,   exchange.getProperty("decisionId",   "unknown", String.class));
        MDC.put(MDC_ROUTE_ID,      String.valueOf(exchange.getFromRouteId()));
        MDC.put(MDC_EXCHANGE_ID,   exchange.getExchangeId());
        MDC.put(MDC_RETRY_ATTEMPT,
                String.valueOf(exchange.getIn().getHeader(Exchange.REDELIVERY_COUNTER, 0, Integer.class)));
    }

    private void clearMdc() {
        MDC.remove(MDC_DECISION_ID);
        MDC.remove(MDC_ROUTE_ID);
        MDC.remove(MDC_EXCHANGE_ID);
        MDC.remove(MDC_RETRY_ATTEMPT);
    }
}