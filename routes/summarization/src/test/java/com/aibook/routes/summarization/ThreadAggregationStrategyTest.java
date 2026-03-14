package com.aibook.routes.summarization;

import com.aibook.core.dto.DocumentContent;
import com.aibook.core.dto.EmailMessage;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ThreadAggregationStrategy}.
 *
 * <p>Uses real {@link DefaultCamelContext} and {@link DefaultExchange} instances
 * to exercise the strategy without starting a full Spring context.
 */
class ThreadAggregationStrategyTest {

    private DefaultCamelContext camelContext;
    private ThreadAggregationStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        strategy = new ThreadAggregationStrategy();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Exchange newExchange(Object body, String correlationId, String sourceType) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        ex.getIn().setHeader("correlationId", correlationId);
        ex.getIn().setHeader("SourceType",    sourceType);
        return ex;
    }

    // ── aggregate() — first message (oldExchange == null) ────────────────────

    @Test
    @DisplayName("First message creates threadParts list with one entry")
    void firstMessage_createsPartsList() {
        Exchange first = newExchange("Hello World", "corr-001", "email");

        Exchange result = strategy.aggregate(null, first);

        @SuppressWarnings("unchecked")
        List<String> parts = result.getProperty(ThreadAggregationStrategy.PROP_THREAD_PARTS, List.class);
        assertThat(parts).isNotNull().hasSize(1);
        assertThat(parts.get(0)).contains("Hello World");
    }

    @Test
    @DisplayName("Second message appends to existing threadParts list")
    void secondMessage_appendsToList() {
        Exchange first  = newExchange("First message", "corr-001", "email");
        Exchange second = newExchange("Second message", "corr-001", "email");

        Exchange agg1 = strategy.aggregate(null, first);
        Exchange agg2 = strategy.aggregate(agg1, second);

        @SuppressWarnings("unchecked")
        List<String> parts = agg2.getProperty(ThreadAggregationStrategy.PROP_THREAD_PARTS, List.class);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("First message");
        assertThat(parts.get(1)).contains("Second message");
    }

    @Test
    @DisplayName("Three messages aggregated correctly in order")
    void threeMessages_aggregatedInOrder() {
        Exchange ex1 = newExchange("Msg A", "corr-x", "email");
        Exchange ex2 = newExchange("Msg B", "corr-x", "email");
        Exchange ex3 = newExchange("Msg C", "corr-x", "email");

        Exchange agg = strategy.aggregate(null, ex1);
        agg = strategy.aggregate(agg, ex2);
        agg = strategy.aggregate(agg, ex3);

        @SuppressWarnings("unchecked")
        List<String> parts = agg.getProperty(ThreadAggregationStrategy.PROP_THREAD_PARTS, List.class);
        assertThat(parts).hasSize(3);
    }

    // ── aggregate() — SourceType merging ─────────────────────────────────────

    @Test
    @DisplayName("Mixed sourceTypes upgrade SourceType header to 'thread'")
    void mixedSourceTypes_upgradeToThread() {
        Exchange email = newExchange("Email body", "corr-2", "email");
        Exchange doc   = newExchange("Doc body",   "corr-2", "document");

        Exchange agg = strategy.aggregate(null, email);
        agg = strategy.aggregate(agg, doc);

        assertThat(agg.getIn().getHeader("SourceType", String.class)).isEqualTo("thread");
    }

    @Test
    @DisplayName("Same sourceType is preserved after aggregation")
    void sameSourceType_preserved() {
        Exchange e1 = newExchange("Email 1", "corr-3", "email");
        Exchange e2 = newExchange("Email 2", "corr-3", "email");

        Exchange agg = strategy.aggregate(null, e1);
        agg = strategy.aggregate(agg, e2);

        assertThat(agg.getIn().getHeader("SourceType", String.class)).isEqualTo("email");
    }

    // ── extractText() — body type handling ───────────────────────────────────

    @Test
    @DisplayName("extractText: EmailMessage includes sender and subject")
    void extractText_emailMessage_includesSenderAndSubject() {
        EmailMessage email = new EmailMessage(
                "msg-1", "thread-1", "Budget Review",
                "Please review the budget.", "alice@example.com",
                Instant.parse("2026-03-01T09:00:00Z"), List.of());

        Exchange ex = newExchange(email, "c1", "email");
        String text = strategy.extractText(ex);

        assertThat(text).contains("alice@example.com");
        assertThat(text).contains("Budget Review");
        assertThat(text).contains("Please review the budget.");
    }

    @Test
    @DisplayName("extractText: DocumentContent includes fileName and extracted text")
    void extractText_documentContent_includesFileName() {
        DocumentContent doc = new DocumentContent(
                "doc-1", "spec.txt", null, "Technical specification content.",
                "text/plain", Map.of());

        Exchange ex = newExchange(doc, "c2", "document");
        String text = strategy.extractText(ex);

        assertThat(text).contains("spec.txt");
        assertThat(text).contains("Technical specification content.");
    }

    @Test
    @DisplayName("extractText: plain String body passed through")
    void extractText_plainString_passedThrough() {
        Exchange ex = newExchange("Just a plain string", "c3", "document");
        assertThat(strategy.extractText(ex)).isEqualTo("Just a plain string");
    }

    @Test
    @DisplayName("extractText: null body returns empty string")
    void extractText_nullBody_returnsEmpty() {
        Exchange ex = newExchange(null, "c4", "email");
        assertThat(strategy.extractText(ex)).isEmpty();
    }

    // ── buildThreadContext() ──────────────────────────────────────────────────

    @Test
    @DisplayName("buildThreadContext includes part markers and total count")
    void buildThreadContext_includesPartMarkersAndCount() {
        List<String> parts = List.of("Part one content", "Part two content", "Part three content");
        String context = strategy.buildThreadContext(parts);

        assertThat(context).contains("Total parts: 3");
        assertThat(context).contains("[Part 1 of 3]");
        assertThat(context).contains("[Part 2 of 3]");
        assertThat(context).contains("[Part 3 of 3]");
        assertThat(context).contains("Part one content");
        assertThat(context).contains("Part two content");
        assertThat(context).contains("Part three content");
    }

    @Test
    @DisplayName("buildThreadContext single part has no separator lines")
    void buildThreadContext_singlePart_noSeparator() {
        List<String> parts = List.of("Only content");
        String context = strategy.buildThreadContext(parts);

        assertThat(context).contains("[Part 1 of 1]");
        assertThat(context).contains("Only content");
        assertThat(context).doesNotContain("[Part 2");
        // No trailing separator expected for single part
        assertThat(context).doesNotContain("\n\n---\n\n");
    }

    @Test
    @DisplayName("buildThreadContext header banner is always present")
    void buildThreadContext_hasBanner() {
        List<String> parts = List.of("content");
        String context = strategy.buildThreadContext(parts);

        assertThat(context).startsWith("=== EMAIL THREAD / DOCUMENT CONTEXT ===");
    }

    // ── onCompletion() ────────────────────────────────────────────────────────

    @Test
    @DisplayName("onCompletion: sets body to structured context string")
    void onCompletion_setsBodyToStructuredContext() {
        Exchange ex = newExchange("Part A", "corr-5", "email");
        Exchange agg = strategy.aggregate(null, ex);
        agg = strategy.aggregate(agg, newExchange("Part B", "corr-5", "email"));

        strategy.onCompletion(agg);

        String body = agg.getIn().getBody(String.class);
        assertThat(body).contains("=== EMAIL THREAD / DOCUMENT CONTEXT ===");
        assertThat(body).contains("Total parts: 2");
        assertThat(body).contains("Part A");
        assertThat(body).contains("Part B");
    }

    @Test
    @DisplayName("onCompletion: sets threadContext exchange property")
    void onCompletion_setsThreadContextProperty() {
        Exchange ex = newExchange("Content", "corr-6", "document");
        Exchange agg = strategy.aggregate(null, ex);

        strategy.onCompletion(agg);

        String prop = agg.getProperty(ThreadAggregationStrategy.PROP_THREAD_CONTEXT, String.class);
        assertThat(prop).isNotBlank();
        assertThat(prop).isEqualTo(agg.getIn().getBody(String.class));
    }

    @Test
    @DisplayName("onCompletion: multiple parts upgrade SourceType to 'thread'")
    void onCompletion_multipleParts_upgradeSourceTypeToThread() {
        Exchange ex1 = newExchange("Email 1", "corr-7", "email");
        Exchange agg = strategy.aggregate(null, ex1);
        agg = strategy.aggregate(agg, newExchange("Email 2", "corr-7", "email"));

        strategy.onCompletion(agg);

        assertThat(agg.getIn().getHeader("SourceType", String.class)).isEqualTo("thread");
    }

    @Test
    @DisplayName("onCompletion: single part preserves original SourceType")
    void onCompletion_singlePart_preservesSourceType() {
        Exchange ex = newExchange("Only email", "corr-8", "email");
        Exchange agg = strategy.aggregate(null, ex);

        strategy.onCompletion(agg);

        // Single part → SourceType is not upgraded to "thread"
        assertThat(agg.getIn().getHeader("SourceType", String.class)).isEqualTo("email");
    }

    @Test
    @DisplayName("onCompletion: empty threadParts sets empty body gracefully")
    void onCompletion_emptyParts_setsEmptyBody() {
        Exchange ex = new DefaultExchange(camelContext);
        // Don't call aggregate() — no PROP_THREAD_PARTS set

        strategy.onCompletion(ex);

        assertThat(ex.getIn().getBody(String.class)).isEmpty();
    }
}
