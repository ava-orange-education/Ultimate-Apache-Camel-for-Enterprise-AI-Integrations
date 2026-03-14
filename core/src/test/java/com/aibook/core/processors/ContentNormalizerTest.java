package com.aibook.core.processors;

import com.aibook.core.dto.DocumentContent;
import com.aibook.core.dto.EmailMessage;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContentNormalizer}.
 */
class ContentNormalizerTest {

    private final ContentNormalizer normalizer = new ContentNormalizer();
    private DefaultCamelContext camelContext;

    @BeforeEach
    void setUp() {
        camelContext = new DefaultCamelContext();
    }

    // ── normalize() unit tests (pure logic, no Camel required) ───────────────

    @Test
    void normalize_stripsHtmlTags() {
        String result = normalizer.normalize("<p>Hello <b>World</b></p>");
        assertThat(result).doesNotContain("<p>", "<b>", "</b>", "</p>");
        assertThat(result).contains("Hello").contains("World");
    }

    @Test
    void normalize_stripsHtmlEntities() {
        String result = normalizer.normalize("cats &amp; dogs &lt;3");
        assertThat(result).doesNotContain("&amp;", "&lt;");
        assertThat(result).contains("cats").contains("dogs");
    }

    @Test
    void normalize_collapsesMimeHeaders() {
        String mime = "MIME-Version: 1.0\nContent-Type: text/plain\nHello world";
        String result = normalizer.normalize(mime);
        assertThat(result).doesNotContain("MIME-Version:", "Content-Type:");
        assertThat(result).contains("Hello world");
    }

    @Test
    void normalize_collapsesMultipleBlankLines() {
        String text = "Line 1\n\n\n\n\nLine 2";
        String result = normalizer.normalize(text);
        assertThat(result).doesNotContain("\n\n\n");
        assertThat(result).contains("Line 1").contains("Line 2");
    }

    @Test
    void normalize_collapsesInlineWhitespace() {
        String text = "word1   word2\t\tword3";
        String result = normalizer.normalize(text);
        assertThat(result).doesNotContain("   ").doesNotContain("\t\t");
    }

    @Test
    void normalize_convertsRfc2822DateToIso8601() {
        String text = "Received on Mon, 02 Mar 2026 09:15:00 +0000 by server";
        String result = normalizer.normalize(text);
        assertThat(result).doesNotContain("Mon, 02 Mar 2026");
        assertThat(result).containsPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void normalize_returnsEmptyStringForNullInput() {
        assertThat(normalizer.normalize(null)).isEmpty();
        assertThat(normalizer.normalize("")).isEmpty();
        assertThat(normalizer.normalize("   ")).isEmpty();
    }

    // ── process() tests via Exchange ──────────────────────────────────────────

    @Test
    void process_emailMessage_setsContentTypeHeader() throws Exception {
        Exchange exchange = exchangeWithBody(
                new EmailMessage("m1", "t1", "Hello", "<p>Body</p>",
                        "alice@test.com", Instant.now(), List.of()));

        normalizer.process(exchange);

        assertThat(exchange.getIn().getHeader("contentType")).isEqualTo("email");
        assertThat(exchange.getIn().getHeader("normalizedAt")).isNotNull();
        String body = exchange.getIn().getBody(String.class);
        assertThat(body).contains("Hello").doesNotContain("<p>");
    }

    @Test
    void process_documentContent_setsContentTypeFromDocument() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-1", "test.pdf", null, "Extracted text content.", "application/pdf", Map.of());
        Exchange exchange = exchangeWithBody(doc);

        normalizer.process(exchange);

        assertThat(exchange.getIn().getHeader("contentType")).isEqualTo("application/pdf");
        assertThat(exchange.getIn().getBody(String.class)).contains("Extracted text");
    }

    @Test
    void process_plainString_usesDefaultContentType() throws Exception {
        Exchange exchange = exchangeWithBody("Plain text body");

        normalizer.process(exchange);

        assertThat(exchange.getIn().getHeader("contentType")).isEqualTo("text/plain");
        assertThat(exchange.getIn().getBody(String.class)).isEqualTo("Plain text body");
    }

    @Test
    void process_nullBody_setsEmptyBodyAndDefaultContentType() throws Exception {
        Exchange exchange = exchangeWithBody(null);

        normalizer.process(exchange);

        assertThat(exchange.getIn().getBody(String.class)).isEmpty();
        assertThat(exchange.getIn().getHeader("contentType")).isEqualTo("text/plain");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Exchange exchangeWithBody(Object body) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getIn().setBody(body);
        return exchange;
    }
}