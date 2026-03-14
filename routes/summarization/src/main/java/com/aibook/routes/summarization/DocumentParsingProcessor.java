package com.aibook.routes.summarization;

import com.aibook.core.dto.DocumentContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses an inbound document exchange body into a structured {@link DocumentContent}.
 *
 * <p>Accepted body formats:
 * <ol>
 *   <li>{@link DocumentContent} — passed through unchanged.</li>
 *   <li>JSON string / byte[] — parsed via Jackson; supports both the exact DTO
 *       shape and the synthetic dataset shape (fields: content, title, mimeType, etc.).</li>
 *   <li>Raw plain text or Markdown — extracted as-is with section headings preserved.</li>
 * </ol>
 *
 * <p>After parsing:
 * <ul>
 *   <li>Exchange body is replaced with the {@link DocumentContent}.</li>
 *   <li>Header {@code correlationId} is set to {@code documentId}.</li>
 *   <li>Header {@code SourceType} is set to {@code "document"}.</li>
 *   <li>Header {@code sectionHeadings} contains comma-separated Markdown headings found in the text.</li>
 * </ul>
 */
@Component
public class DocumentParsingProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingProcessor.class);

    /** Markdown heading pattern: # Heading, ## Sub-heading, etc. */
    private static final Pattern MARKDOWN_HEADING = Pattern.compile(
            "^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /** Plain-text section label: "SECTION:", "CHAPTER 1:", etc. */
    private static final Pattern PLAIN_SECTION = Pattern.compile(
            "^([A-Z][A-Z0-9 ]{2,40}):?\\s*$", Pattern.MULTILINE);

    /** Detect JSON bodies */
    private static final Pattern JSON_START = Pattern.compile("^\\s*[{\\[]");

    private final ObjectMapper objectMapper;

    public DocumentParsingProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Object body = exchange.getIn().getBody();

        DocumentContent doc = switch (body) {
            case DocumentContent dc -> dc;                         // already parsed
            case byte[] bytes       -> parseJson(new String(bytes));
            case String s when JSON_START.matcher(s).find() -> parseJson(s);
            case String s           -> parsePlainText(s, exchange);
            case java.io.InputStream is -> {                       // InputStreamCache / any stream
                String text = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                yield JSON_START.matcher(text).find() ? parseJson(text) : parsePlainText(text, exchange);
            }
            default -> {
                log.warn("DocumentParsingProcessor: unexpected body type {} — wrapping as text",
                        body == null ? "null" : body.getClass().getSimpleName());
                yield buildMinimal(body == null ? "" : body.toString(), exchange);
            }
        };

        // ── Extract section headings ───────────────────────────────────────────
        String headings = extractHeadings(doc.extractedText());

        // ── Set exchange state ────────────────────────────────────────────────
        exchange.getIn().setBody(doc);
        exchange.getIn().setHeader("correlationId", doc.documentId());
        exchange.getIn().setHeader("SourceType",    "document");
        exchange.getIn().setHeader("PipelineId",
                "doc-" + doc.documentId() + "-" + exchange.getExchangeId());
        if (!headings.isBlank()) {
            exchange.getIn().setHeader("sectionHeadings", headings);
        }

        log.debug("DocumentParsingProcessor: parsed documentId={} fileName={} textLen={}",
                doc.documentId(), doc.fileName(), doc.extractedText().length());
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    /**
     * Parse a JSON body. Accepts both the exact {@link DocumentContent} DTO shape
     * and the synthetic dataset shape used in {@code datasets/synthetic/text/}.
     */
    DocumentContent parseJson(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);

        // Prefer explicit documentId; fall back to "id" or auto-generate
        String documentId = textOrRandom(node, "documentId", "id", "doc-id");
        String fileName   = textOrEmpty(node, "fileName", "name");
        String title      = textOrEmpty(node, "title");
        // Accept "extractedText", "content", "text", or "body" from various sources
        String text       = textOrEmpty(node, "extractedText", "content", "text", "body");
        String mimeType   = textOrEmpty(node, "contentType", "mimeType", "mime-type");
        if (mimeType.isBlank()) mimeType = "text/plain";

        // Build metadata from any "metadata" sub-object or known top-level fields
        Map<String, String> metadata = new HashMap<>();
        JsonNode metaNode = node.get("metadata");
        if (metaNode != null && metaNode.isObject()) {
            metaNode.fields().forEachRemaining(e -> metadata.put(e.getKey(), e.getValue().asText()));
        }
        // Promote known top-level fields into metadata
        for (String key : new String[]{"sourceUri", "ingestedAt", "author", "language"}) {
            JsonNode n = node.get(key);
            if (n != null && n.isTextual()) metadata.put(key, n.asText());
        }

        return new DocumentContent(documentId, fileName, title, text, mimeType, metadata);
    }

    /**
     * Parse a raw plain text / Markdown body. The filename is taken from
     * {@code CamelFileName} header (file-drop polling) when available.
     */
    DocumentContent parsePlainText(String text, Exchange exchange) {
        String documentId = UUID.randomUUID().toString();
        String fileName   = exchange.getIn().getHeader("CamelFileName", "", String.class);
        return new DocumentContent(documentId, fileName, "", text, "text/plain", Map.of());
    }

    private DocumentContent buildMinimal(String text, Exchange exchange) {
        String fileName = exchange.getIn().getHeader("CamelFileName", "", String.class);
        return new DocumentContent(UUID.randomUUID().toString(), fileName, "", text, "text/plain", Map.of());
    }

    // ── Section heading extraction ─────────────────────────────────────────────

    /**
     * Returns a comma-separated string of headings found in the text,
     * supporting both Markdown ({@code # Heading}) and plain-text ({@code SECTION:}) styles.
     */
    String extractHeadings(String text) {
        if (text == null || text.isBlank()) return "";

        StringBuilder sb = new StringBuilder();

        // Try Markdown headings first
        java.util.regex.Matcher mdMatcher = MARKDOWN_HEADING.matcher(text);
        while (mdMatcher.find()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(mdMatcher.group(2).trim());
        }

        // If no Markdown headings found, try plain-text section labels
        if (sb.isEmpty()) {
            java.util.regex.Matcher ptMatcher = PLAIN_SECTION.matcher(text);
            while (ptMatcher.find()) {
                String label = ptMatcher.group(1).trim();
                if (label.length() >= 3) {   // ignore very short matches
                    if (!sb.isEmpty()) sb.append(", ");
                    sb.append(label);
                }
            }
        }

        return sb.toString();
    }

    // ── JSON field helpers ────────────────────────────────────────────────────

    private String textOrRandom(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode n = node.get(key);
            if (n != null && n.isTextual() && !n.asText().isBlank()) return n.asText();
        }
        return UUID.randomUUID().toString();
    }

    private String textOrEmpty(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode n = node.get(key);
            if (n != null && n.isTextual()) return n.asText();
        }
        return "";
    }
}
