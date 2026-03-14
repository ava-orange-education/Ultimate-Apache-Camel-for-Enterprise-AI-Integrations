package com.aibook.routes.summarization;

import com.aibook.core.dto.EmailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * Pure unit tests for {@link EmailParsingProcessor}.
 * No Spring context — uses a real ObjectMapper and a minimal Camel context.
 */
class EmailParsingProcessorTest {

    private DefaultCamelContext camelContext;
    private EmailParsingProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        processor = new EmailParsingProcessor(mapper);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(Object body) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        return ex;
    }

    // ── EmailMessage pass-through ─────────────────────────────────────────────

    @Test
    @DisplayName("EmailMessage body is passed through unchanged")
    void emailMessageBody_passedThrough() throws Exception {
        EmailMessage email = new EmailMessage(
                "msg-001", "thread-001", "Subject", "Body text",
                "alice@example.com", Instant.now(), List.of());
        Exchange ex = exchange(email);

        processor.process(ex);

        assertThat(ex.getIn().getBody()).isInstanceOf(EmailMessage.class);
        EmailMessage result = (EmailMessage) ex.getIn().getBody();
        assertThat(result.messageId()).isEqualTo("msg-001");
        assertThat(result.subject()).isEqualTo("Subject");
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON string body is parsed into EmailMessage")
    void jsonString_parsedToEmailMessage() throws Exception {
        String json = """
                {
                  "messageId": "json-001",
                  "threadId":  "thread-json",
                  "subject":   "JSON Subject",
                  "body":      "JSON body content",
                  "sender":    "bob@example.com",
                  "receivedAt": "2026-03-01T09:00:00Z"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        EmailMessage result = (EmailMessage) ex.getIn().getBody();
        assertThat(result.messageId()).isEqualTo("json-001");
        assertThat(result.threadId()).isEqualTo("thread-json");
        assertThat(result.subject()).isEqualTo("JSON Subject");
        assertThat(result.body()).isEqualTo("JSON body content");
        assertThat(result.sender()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("JSON uses 'from' field as sender when 'sender' is absent")
    void jsonBody_usesSenderAliases() throws Exception {
        String json = """
                {
                  "from": "carol@example.com",
                  "subject": "Hello",
                  "body": "Body text"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        EmailMessage result = (EmailMessage) ex.getIn().getBody();
        assertThat(result.sender()).isEqualTo("carol@example.com");
    }

    @Test
    @DisplayName("JSON with 'content' field maps to body")
    void jsonBody_contentFieldMapsToBody() throws Exception {
        String json = """
                {
                  "messageId": "m-x",
                  "subject":   "S",
                  "content":   "Content as body",
                  "from":      "x@y.com"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        EmailMessage result = (EmailMessage) ex.getIn().getBody();
        assertThat(result.body()).isEqualTo("Content as body");
    }

    // ── Raw RFC-2822 parsing ──────────────────────────────────────────────────

    @Test
    @DisplayName("Raw RFC-2822 text is heuristically parsed")
    void rawRfc2822_heuristicallyParsed() throws Exception {
        String raw = """
                From: dave@example.com
                Subject: RFC 2822 Test
                Message-ID: raw-msg-001
                Date: Mon, 01 Mar 2026 09:15:00 +0000

                This is the email body.
                It spans multiple lines.
                """;
        Exchange ex = exchange(raw);

        processor.process(ex);

        EmailMessage result = (EmailMessage) ex.getIn().getBody();
        assertThat(result.sender()).isEqualTo("dave@example.com");
        assertThat(result.subject()).isEqualTo("RFC 2822 Test");
        assertThat(result.messageId()).isEqualTo("raw-msg-001");
        assertThat(result.body()).contains("This is the email body.");
        assertThat(result.body()).doesNotContain("From:");  // headers stripped
    }

    // ── correlationId header ──────────────────────────────────────────────────

    @Test
    @DisplayName("correlationId header is set to threadId when threadId is non-blank")
    void correlationId_fromThreadId() throws Exception {
        String json = """
                {
                  "messageId": "m1",
                  "threadId":  "thread-abc",
                  "body":      "body"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        assertThat(ex.getIn().getHeader("correlationId")).isEqualTo("thread-abc");
    }

    @Test
    @DisplayName("correlationId falls back to messageId when threadId is blank")
    void correlationId_fallbackToMessageId() throws Exception {
        String json = """
                {
                  "messageId": "msg-fallback",
                  "threadId":  "",
                  "body":      "body"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        assertThat(ex.getIn().getHeader("correlationId")).isEqualTo("msg-fallback");
    }

    // ── SourceType header ─────────────────────────────────────────────────────

    @Test
    @DisplayName("SourceType header is always set to 'email'")
    void sourceType_alwaysEmail() throws Exception {
        Exchange ex = exchange("{\"body\":\"test\"}");

        processor.process(ex);

        assertThat(ex.getIn().getHeader("SourceType")).isEqualTo("email");
    }

    // ── Quoted text separation ────────────────────────────────────────────────

    @Test
    @DisplayName("separateQuotedText strips '>' prefixed lines into quotedText")
    void separateQuotedText_stripsQuotedLines() {
        String body = "This is my reply.\n> Original line one.\n> Original line two.\nEnd.";

        String[] parts = processor.separateQuotedText(body);

        assertThat(parts[0]).contains("This is my reply.");
        assertThat(parts[0]).doesNotContain("> Original");
        assertThat(parts[1]).contains("> Original line one.");
    }

    @Test
    @DisplayName("separateQuotedText splits on Outlook 'On ... wrote:' separator")
    void separateQuotedText_splitsOnOutlookSeparator() {
        String body = "My actual reply.\n\nOn Mon, 01 Mar 2026 09:00:00 +0000, Alice wrote:\n> Original content.";

        String[] parts = processor.separateQuotedText(body);

        assertThat(parts[0]).contains("My actual reply.");
        assertThat(parts[1]).contains("On Mon");
    }

    @Test
    @DisplayName("separateQuotedText handles body with no quoted text")
    void separateQuotedText_noQuoted_returnsFullBodyAndEmpty() {
        String body = "Clean email body. No quoted text here.";

        String[] parts = processor.separateQuotedText(body);

        assertThat(parts[0]).isEqualTo(body);
        assertThat(parts[1]).isEmpty();
    }

    @Test
    @DisplayName("separateQuotedText handles null body gracefully")
    void separateQuotedText_nullBody_returnsEmptyParts() {
        String[] parts = processor.separateQuotedText(null);

        assertThat(parts[0]).isEmpty();
        assertThat(parts[1]).isEmpty();
    }

    // ── Attachment extraction ─────────────────────────────────────────────────

    @Test
    @DisplayName("Attachment markers in body are captured in attachmentPaths header")
    void attachmentMarkers_capturedInHeader() throws Exception {
        String json = """
                {
                  "messageId": "m-att",
                  "body":      "See [Attachment: report.pdf] and [Attachment: data.xlsx]."
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        String attachments = ex.getIn().getHeader("attachmentPaths", String.class);
        assertThat(attachments).contains("report.pdf");
        assertThat(attachments).contains("data.xlsx");
    }

    @Test
    @DisplayName("Body with no attachment markers sets no attachmentPaths header")
    void noAttachments_noHeader() throws Exception {
        Exchange ex = exchange("{\"messageId\":\"m-no-att\",\"body\":\"Clean body.\"}");

        processor.process(ex);

        assertThat(ex.getIn().getHeader("attachmentPaths")).isNull();
    }

    // ── parseJson — attachmentPaths array ─────────────────────────────────────

    @Test
    @DisplayName("parseJson reads attachmentPaths array from JSON")
    void parseJson_readsAttachmentPathsArray() throws Exception {
        String json = """
                {
                  "messageId": "m-arr",
                  "body": "body",
                  "attachmentPaths": ["file1.pdf", "file2.docx"]
                }""";
        EmailMessage result = processor.parseJson(json);

        assertThat(result.attachmentPaths()).containsExactly("file1.pdf", "file2.docx");
    }
}
