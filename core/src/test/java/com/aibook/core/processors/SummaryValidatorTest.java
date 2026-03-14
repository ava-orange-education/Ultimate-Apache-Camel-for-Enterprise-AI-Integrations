package com.aibook.core.processors;

import com.aibook.core.dto.SummaryResult;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SummaryValidator}.
 */
class SummaryValidatorTest {

    private final SummaryValidator validator = new SummaryValidator();
    private DefaultCamelContext camelContext;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
    }

    private Exchange exchangeWithBody(Object body) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getIn().setBody(body);
        return exchange;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void process_validSummary_setsNoFailureHeaders() throws Exception {
        SummaryResult result = new SummaryResult(
                "src-1", "A perfectly concise and valid summary text.",
                "document", Instant.now(), "llama3.2", false);

        Exchange exchange = exchangeWithBody(result);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed")).isNull();
        assertThat(exchange.getIn().getHeader("validationFailureReason")).isNull();
    }

    // ── Null / wrong type ─────────────────────────────────────────────────────

    @Test
    void process_nullBody_setsValidationFailed() throws Exception {
        Exchange exchange = exchangeWithBody(null);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(exchange.getIn().getHeader("validationFailureReason"))
                .isEqualTo("body_not_summary_result");
    }

    @Test
    void process_wrongBodyType_setsValidationFailed() throws Exception {
        Exchange exchange = exchangeWithBody("I am just a String, not a SummaryResult");
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(exchange.getIn().getHeader("validationFailureReason"))
                .isEqualTo("body_not_summary_result");
    }

    // ── Blank summary ─────────────────────────────────────────────────────────

    @Test
    void process_blankSummaryText_setsValidationFailed() throws Exception {
        SummaryResult result = new SummaryResult(
                "src-2", "   ", "email", Instant.now(), "llama3.2", false);

        Exchange exchange = exchangeWithBody(result);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(exchange.getIn().getHeader("validationFailureReason"))
                .isEqualTo("blank_summary");
    }

    @Test
    void process_emptySummaryText_setsValidationFailed() throws Exception {
        SummaryResult result = new SummaryResult(
                "src-3", "", "email", Instant.now(), "llama3.2", false);

        Exchange exchange = exchangeWithBody(result);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(exchange.getIn().getHeader("validationFailureReason"))
                .isEqualTo("blank_summary");
    }

    // ── Over-length summary ───────────────────────────────────────────────────

    @Test
    void process_summaryExceeds2000Chars_setsValidationFailed() throws Exception {
        String longText = "x".repeat(SummaryValidator.MAX_SUMMARY_CHARS + 1);
        SummaryResult result = new SummaryResult(
                "src-4", longText, "document", Instant.now(), "llama3.2", false);

        Exchange exchange = exchangeWithBody(result);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed", Boolean.class)).isTrue();
        assertThat(exchange.getIn().getHeader("validationFailureReason"))
                .isEqualTo("summary_too_long");
    }

    @Test
    void process_summaryExactly2000Chars_passes() throws Exception {
        String exactText = "a".repeat(SummaryValidator.MAX_SUMMARY_CHARS);
        SummaryResult result = new SummaryResult(
                "src-5", exactText, "document", Instant.now(), "llama3.2", false);

        Exchange exchange = exchangeWithBody(result);
        validator.process(exchange);

        assertThat(exchange.getIn().getHeader("validationFailed")).isNull();
    }
}