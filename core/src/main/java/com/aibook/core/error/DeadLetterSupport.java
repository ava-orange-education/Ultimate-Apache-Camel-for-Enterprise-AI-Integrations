package com.aibook.core.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dead-letter sink route for the AI pipeline.
 *
 * <p>Route ID: {@code dead-letter-channel}<br>
 * Entry URI: {@code direct:deadLetter}
 *
 * <p>On receipt the route:
 * <ol>
 *   <li>Logs the full exchange details (route, exception, body) at ERROR level</li>
 *   <li>Serializes a structured regression-case JSON payload</li>
 *   <li>Writes the JSON to {@code ${aibook.dead-letter.log-dir}}
 *       with filename {@code dlc-<routeId>-<timestamp>.json}</li>
 * </ol>
 *
 * <p>The written files can be replayed by the test harness in
 * {@code tools/test-harness/} to prevent regressions.
 */
@Component
public class DeadLetterSupport extends RouteBuilder {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterSupport.class);

    @Value("${aibook.dead-letter.log-dir:${java.io.tmpdir}/aibook/dead-letter}")
    private String deadLetterDir;

    private final ObjectMapper mapper;

    public DeadLetterSupport() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void configure() {

        from("direct:deadLetter")
                .routeId("dead-letter-channel")

                // ── 1. Structured log ─────────────────────────────────────────
                .process(exchange -> {
                    Exception cause = exchange.getProperty(
                            Exchange.EXCEPTION_CAUGHT, Exception.class);
                    log.error(
                            "☠️  Dead-letter | route={} decisionId={} exchangeId={} "
                          + "exceptionClass={} message={} body={}",
                            exchange.getFromRouteId(),
                            exchange.getProperty("decisionId", "unknown", String.class),
                            exchange.getExchangeId(),
                            cause != null ? cause.getClass().getName()  : "none",
                            cause != null ? cause.getMessage()          : "none",
                            truncate(exchange.getIn().getBody()));
                })

                // ── 2. Build regression-case JSON and set as body ─────────────
                .process(exchange -> {
                    Exception cause = exchange.getProperty(
                            Exchange.EXCEPTION_CAUGHT, Exception.class);

                    Map<String, Object> regressionCase = new LinkedHashMap<>();
                    regressionCase.put("capturedAt",    Instant.now().toString());
                    regressionCase.put("routeId",       exchange.getFromRouteId());
                    regressionCase.put("exchangeId",    exchange.getExchangeId());
                    regressionCase.put("decisionId",    exchange.getProperty("decisionId", "unknown", String.class));
                    regressionCase.put("stage",         exchange.getProperty("stage",      "unknown", String.class));
                    regressionCase.put("modelVersion",  exchange.getProperty("modelVersion","unknown", String.class));
                    regressionCase.put("exceptionClass",
                            cause != null ? cause.getClass().getName() : null);
                    regressionCase.put("exceptionMessage",
                            cause != null ? cause.getMessage()         : null);
                    regressionCase.put("headers",       exchange.getIn().getHeaders());
                    regressionCase.put("body",          truncate(exchange.getIn().getBody()));

                    String json = mapper.writeValueAsString(regressionCase);
                    exchange.getIn().setBody(json);

                    // Filename: dlc-<routeId>-<ISO timestamp>.json
                    String routeId  = exchange.getFromRouteId() != null
                            ? exchange.getFromRouteId() : "unknown-route";
                    String fileName = "dlc-" + sanitize(routeId) + "-"
                            + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
                                     .withZone(java.time.ZoneOffset.UTC)
                                     .format(Instant.now())
                            + ".json";
                    exchange.getIn().setHeader(Exchange.FILE_NAME, fileName);
                })

                // ── 3. Write JSON file ────────────────────────────────────────
                .toD("file:" + deadLetterDir
                        + "?fileName=${header.CamelFileName}&autoCreate=true")

                .log("Dead-letter case written: " + deadLetterDir
                        + "/${header.CamelFileName}")

                // ── 4. Reset to clean HTTP error response ─────────────────────
                // Clear all headers that may carry large exception details so that
                // Tomcat's HeadersTooLargeException is avoided when Camel writes
                // the response back over HTTP for synchronous REST calls.
                .process(exchange -> {
                    String dlcFile = exchange.getIn().getHeader(Exchange.FILE_NAME, String.class);
                    // Wipe all headers, then set only the minimal HTTP response headers
                    exchange.getIn().removeHeaders("*");
                    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
                    exchange.getIn().setHeader("Content-Type", "application/json");
                    exchange.getIn().setBody("{\"error\":\"Request failed and was captured in dead-letter\","
                            + "\"ref\":\"" + (dlcFile != null ? dlcFile : "unknown") + "\"}");;
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String truncate(Object obj) {
        if (obj == null) return "";
        String s = obj.toString();
        return s.length() > 4_000 ? s.substring(0, 4_000) + "… [truncated]" : s;
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}