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
 * Pure unit tests for {@link MultiDocContextBuilder}.
 */
class MultiDocContextBuilderTest {

    private DefaultCamelContext camelContext;
    private MultiDocContextBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        builder = new MultiDocContextBuilder();
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(Object body, String sourceType) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        ex.getIn().setHeader("SourceType", sourceType);
        return ex;
    }

    // ── process() — exchange state ─────────────────────────────────────────────

    @Test
    @DisplayName("process() replaces body with annotated context string")
    void process_replacesBodyWithAnnotatedContext() throws Exception {
        Exchange ex = exchange("Raw content for context.", "document");

        builder.process(ex);

        String body = ex.getIn().getBody(String.class);
        assertThat(body).contains("MULTI-DOCUMENT SUMMARIZATION CONTEXT");
        assertThat(body).contains("Raw content for context.");
        assertThat(body).contains("END OF CONTEXT");
    }

    @Test
    @DisplayName("process() sets contextCharCount header")
    void process_setsContextCharCountHeader() throws Exception {
        Exchange ex = exchange("Some content.", "document");

        builder.process(ex);

        Integer charCount = ex.getIn().getHeader("contextCharCount", Integer.class);
        assertThat(charCount).isGreaterThan(0);
        assertThat(charCount).isEqualTo(ex.getIn().getBody(String.class).length());
    }

    @Test
    @DisplayName("process() sets contextBuiltAt header as ISO-8601 string")
    void process_setsContextBuiltAtHeader() throws Exception {
        Exchange ex = exchange("Content.", "email");

        builder.process(ex);

        String builtAt = ex.getIn().getHeader("contextBuiltAt", String.class);
        assertThat(builtAt).isNotBlank();
        // Should parse without error as an Instant
        assertThat(Instant.parse(builtAt)).isNotNull();
    }

    @Test
    @DisplayName("process() sets multiDocContext exchange property")
    void process_setsMultiDocContextProperty() throws Exception {
        Exchange ex = exchange("Property content.", "document");

        builder.process(ex);

        String prop = ex.getProperty("multiDocContext", String.class);
        assertThat(prop).isNotBlank();
        assertThat(prop).isEqualTo(ex.getIn().getBody(String.class));
    }

    // ── resolveContent() — body type handling ─────────────────────────────────

    @Test
    @DisplayName("resolveContent() prefers threadContext property over body")
    void resolveContent_prefersThreadContextProperty() {
        Exchange ex = exchange("This body should be ignored.", "thread");
        ex.setProperty(ThreadAggregationStrategy.PROP_THREAD_CONTEXT, "Thread context string");

        String content = builder.resolveContent("This body should be ignored.", ex);

        assertThat(content).isEqualTo("Thread context string");
    }

    @Test
    @DisplayName("resolveContent() uses String body when no threadContext property")
    void resolveContent_usesStringBody() {
        Exchange ex = exchange("Plain string body.", "document");

        String content = builder.resolveContent("Plain string body.", ex);

        assertThat(content).isEqualTo("Plain string body.");
    }

    @Test
    @DisplayName("resolveContent() formats EmailMessage body with metadata")
    void resolveContent_formatsEmailMessage() {
        EmailMessage email = new EmailMessage(
                "m1", "t1", "Project Update",
                "Please review the attached proposal.",
                "alice@example.com", Instant.parse("2026-03-01T09:00:00Z"), List.of());
        Exchange ex = exchange(email, "email");

        String content = builder.resolveContent(email, ex);

        assertThat(content).contains("alice@example.com");
        assertThat(content).contains("Project Update");
        assertThat(content).contains("Please review the attached proposal.");
    }

    @Test
    @DisplayName("resolveContent() formats DocumentContent with fileName and type")
    void resolveContent_formatsDocumentContent() {
        DocumentContent doc = new DocumentContent(
                "d1", "architecture.txt", null,
                "The system architecture is based on microservices.",
                "text/plain", Map.of());
        Exchange ex = exchange(doc, "document");

        String content = builder.resolveContent(doc, ex);

        assertThat(content).contains("architecture.txt");
        assertThat(content).contains("text/plain");
        assertThat(content).contains("The system architecture is based on microservices.");
    }

    @Test
    @DisplayName("resolveContent() includes sectionHeadings from header")
    void resolveContent_includesSectionHeadingsHeader() {
        DocumentContent doc = new DocumentContent(
                "d2", "spec.txt", null, "Content.", "text/plain", Map.of());
        Exchange ex = exchange(doc, "document");
        ex.getIn().setHeader("sectionHeadings", "Overview, Architecture, Conclusion");

        String content = builder.resolveContent(doc, ex);

        assertThat(content).contains("Overview, Architecture, Conclusion");
    }

    @Test
    @DisplayName("resolveContent() handles null body gracefully")
    void resolveContent_nullBody_returnsEmpty() {
        Exchange ex = exchange(null, "document");

        String content = builder.resolveContent(null, ex);

        assertThat(content).isEmpty();
    }

    // ── buildAnnotatedContext() ────────────────────────────────────────────────

    @Test
    @DisplayName("buildAnnotatedContext includes source type, part count, and char count")
    void buildAnnotatedContext_includesMetadata() {
        String context = builder.buildAnnotatedContext("Content text.", "thread", 3);

        assertThat(context).contains("Source type : thread");
        assertThat(context).contains("Parts       : 3");
        assertThat(context).contains("Total chars : 13");   // "Content text." is 13 chars
        assertThat(context).contains("Content text.");
    }

    @Test
    @DisplayName("buildAnnotatedContext always has header and footer banners")
    void buildAnnotatedContext_hasBanners() {
        String context = builder.buildAnnotatedContext("x", "email", 1);

        assertThat(context).contains("MULTI-DOCUMENT SUMMARIZATION CONTEXT");
        assertThat(context).contains("END OF CONTEXT");
        assertThat(context).contains("=".repeat(60));
    }

    // ── truncate() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("truncate() returns text unchanged when under limit")
    void truncate_shortText_unchanged() {
        String text = "Short text.";
        assertThat(builder.truncate(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("truncate() cuts text at MAX_CONTEXT_CHARS and appends marker")
    void truncate_longText_cutAndMarked() {
        String longText = "A".repeat(MultiDocContextBuilder.MAX_CONTEXT_CHARS + 500);

        String truncated = builder.truncate(longText);

        assertThat(truncated).hasSize(
                MultiDocContextBuilder.MAX_CONTEXT_CHARS +
                "\n\n[... content truncated at ".length() +
                String.valueOf(MultiDocContextBuilder.MAX_CONTEXT_CHARS).length() +
                " characters ...]".length());
        assertThat(truncated).contains("[... content truncated at");
    }

    @Test
    @DisplayName("truncate() handles null input gracefully")
    void truncate_nullInput_returnsEmpty() {
        assertThat(builder.truncate(null)).isEmpty();
    }

    @Test
    @DisplayName("truncate() passes through text exactly at MAX_CONTEXT_CHARS")
    void truncate_exactlyAtLimit_notTruncated() {
        String text = "B".repeat(MultiDocContextBuilder.MAX_CONTEXT_CHARS);
        assertThat(builder.truncate(text)).isEqualTo(text);
    }

    // ── threadParts property integration ──────────────────────────────────────

    @Test
    @DisplayName("process() uses threadParts.size() for part count in context")
    void process_usesThreadPartsForPartCount() throws Exception {
        Exchange ex = exchange("context string", "thread");
        // Simulate what ThreadAggregationStrategy sets
        ex.setProperty(ThreadAggregationStrategy.PROP_THREAD_PARTS,
                List.of("part1", "part2", "part3"));

        builder.process(ex);

        String body = ex.getIn().getBody(String.class);
        assertThat(body).contains("Parts       : 3");
    }
}
