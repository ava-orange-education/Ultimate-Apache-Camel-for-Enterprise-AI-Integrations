package com.aibook.routes.summarization;

import com.aibook.core.dto.DocumentContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link DocumentParsingProcessor}.
 */
class DocumentParsingProcessorTest {

    private DefaultCamelContext camelContext;
    private DocumentParsingProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        processor = new DocumentParsingProcessor(new ObjectMapper());
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

    // ── DocumentContent pass-through ──────────────────────────────────────────

    @Test
    @DisplayName("DocumentContent body is passed through unchanged")
    void documentContentBody_passedThrough() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-001", "spec.txt", null, "Technical content.", "text/plain", Map.of());
        Exchange ex = exchange(doc);

        processor.process(ex);

        assertThat(ex.getIn().getBody()).isInstanceOf(DocumentContent.class);
        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.documentId()).isEqualTo("doc-001");
        assertThat(result.fileName()).isEqualTo("spec.txt");
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON string body is parsed into DocumentContent")
    void jsonString_parsedToDocumentContent() throws Exception {
        String json = """
                {
                  "documentId":    "doc-json-001",
                  "fileName":      "report.txt",
                  "extractedText": "Full report text goes here.",
                  "contentType":   "text/plain"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.documentId()).isEqualTo("doc-json-001");
        assertThat(result.fileName()).isEqualTo("report.txt");
        assertThat(result.extractedText()).isEqualTo("Full report text goes here.");
        assertThat(result.contentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("JSON with 'content' field maps to extractedText")
    void jsonWithContentField_mapsToExtractedText() throws Exception {
        String json = """
                {
                  "documentId": "doc-002",
                  "title":      "RAG Introduction",
                  "content":    "RAG stands for Retrieval-Augmented Generation.",
                  "mimeType":   "text/plain"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.extractedText()).isEqualTo("RAG stands for Retrieval-Augmented Generation.");
        assertThat(result.title()).isEqualTo("RAG Introduction");      // title field
        assertThat(result.contentType()).isEqualTo("text/plain");      // mimeType → contentType
    }

    @Test
    @DisplayName("JSON metadata sub-object is captured in metadata map")
    void jsonMetadata_capturedInMap() throws Exception {
        String json = """
                {
                  "documentId":    "doc-003",
                  "extractedText": "text",
                  "metadata": {
                    "author": "AI Book Team",
                    "category": "AI"
                  }
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.metadata()).containsEntry("author", "AI Book Team");
        assertThat(result.metadata()).containsEntry("category", "AI");
    }

    @Test
    @DisplayName("JSON top-level sourceUri is promoted into metadata")
    void jsonSourceUri_promotedIntoMetadata() throws Exception {
        String json = """
                {
                  "documentId":    "doc-004",
                  "extractedText": "text",
                  "sourceUri":     "internal://kb/doc-004"
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.metadata()).containsEntry("sourceUri", "internal://kb/doc-004");
    }

    @Test
    @DisplayName("JSON with missing contentType defaults to text/plain")
    void jsonMissingContentType_defaultsToTextPlain() throws Exception {
        Exchange ex = exchange("{\"documentId\":\"doc-005\",\"extractedText\":\"text\"}");

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.contentType()).isEqualTo("text/plain");
    }

    // ── Plain text parsing ────────────────────────────────────────────────────

    @Test
    @DisplayName("Plain text body is wrapped in DocumentContent")
    void plainText_wrappedInDocumentContent() throws Exception {
        Exchange ex = exchange("This is just plain text content.");

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.extractedText()).isEqualTo("This is just plain text content.");
        assertThat(result.contentType()).isEqualTo("text/plain");
        assertThat(result.documentId()).isNotBlank();
    }

    @Test
    @DisplayName("Plain text with CamelFileName header captures filename")
    void plainText_camelFileName_capturedInFileName() throws Exception {
        Exchange ex = exchange("Content.");
        ex.getIn().setHeader("CamelFileName", "my-document.txt");

        processor.process(ex);

        DocumentContent result = (DocumentContent) ex.getIn().getBody();
        assertThat(result.fileName()).isEqualTo("my-document.txt");
    }

    // ── Exchange headers ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SourceType header is always set to 'document'")
    void sourceTypeHeader_alwaysDocument() throws Exception {
        Exchange ex = exchange("{\"documentId\":\"d1\",\"extractedText\":\"text\"}");

        processor.process(ex);

        assertThat(ex.getIn().getHeader("SourceType")).isEqualTo("document");
    }

    @Test
    @DisplayName("correlationId header is set to documentId")
    void correlationIdHeader_setToDocumentId() throws Exception {
        Exchange ex = exchange("{\"documentId\":\"doc-corr-001\",\"extractedText\":\"text\"}");

        processor.process(ex);

        assertThat(ex.getIn().getHeader("correlationId")).isEqualTo("doc-corr-001");
    }

    @Test
    @DisplayName("PipelineId header is set and contains documentId")
    void pipelineIdHeader_containsDocumentId() throws Exception {
        Exchange ex = exchange("{\"documentId\":\"doc-pip-001\",\"extractedText\":\"text\"}");

        processor.process(ex);

        String pipelineId = ex.getIn().getHeader("PipelineId", String.class);
        assertThat(pipelineId).contains("doc-pip-001");
    }

    // ── Section heading extraction ────────────────────────────────────────────

    @Test
    @DisplayName("extractHeadings: Markdown headings are extracted")
    void extractHeadings_markdownHeadings() {
        String text = "# Overview\n\nSome text.\n\n## Architecture\n\nMore text.\n\n### Details\n\nFinal.";
        String headings = processor.extractHeadings(text);

        assertThat(headings).contains("Overview");
        assertThat(headings).contains("Architecture");
        assertThat(headings).contains("Details");
    }

    @Test
    @DisplayName("extractHeadings: no headings returns empty string")
    void extractHeadings_noHeadings_returnsEmpty() {
        String text = "This is plain text with no headings at all.";
        assertThat(processor.extractHeadings(text)).isEmpty();
    }

    @Test
    @DisplayName("extractHeadings: null text returns empty string")
    void extractHeadings_nullText_returnsEmpty() {
        assertThat(processor.extractHeadings(null)).isEmpty();
    }

    @Test
    @DisplayName("sectionHeadings header is set when Markdown headings found")
    void sectionHeadingsHeader_setWhenHeadingsFound() throws Exception {
        String json = """
                {
                  "documentId":    "doc-h1",
                  "extractedText": "# Section One\\n\\nContent.\\n\\n## Section Two\\n\\nMore."
                }""";
        Exchange ex = exchange(json);

        processor.process(ex);

        String headings = ex.getIn().getHeader("sectionHeadings", String.class);
        assertThat(headings).contains("Section One");
        assertThat(headings).contains("Section Two");
    }

    @Test
    @DisplayName("sectionHeadings header is NOT set when no headings found")
    void sectionHeadingsHeader_absentWhenNoHeadings() throws Exception {
        Exchange ex = exchange("{\"documentId\":\"d-nh\",\"extractedText\":\"plain text only\"}");

        processor.process(ex);

        assertThat(ex.getIn().getHeader("sectionHeadings")).isNull();
    }

    // ── parseJson directly ────────────────────────────────────────────────────

    @Test
    @DisplayName("parseJson uses 'id' field when 'documentId' absent")
    void parseJson_idFieldFallback() throws Exception {
        String json = "{\"id\":\"my-id\",\"extractedText\":\"text\"}";
        DocumentContent doc = processor.parseJson(json);

        assertThat(doc.documentId()).isEqualTo("my-id");
    }

    @Test
    @DisplayName("parseJson auto-generates documentId when all id fields absent")
    void parseJson_autoGeneratesDocumentId() throws Exception {
        String json = "{\"extractedText\":\"text\"}";
        DocumentContent doc = processor.parseJson(json);

        assertThat(doc.documentId()).isNotBlank();
        // Should look like a UUID
        assertThat(doc.documentId()).matches("[0-9a-f-]{36}");
    }
}
