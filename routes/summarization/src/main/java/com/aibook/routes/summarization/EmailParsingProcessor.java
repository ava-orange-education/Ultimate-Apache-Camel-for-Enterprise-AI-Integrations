package com.aibook.routes.summarization;

import com.aibook.core.dto.EmailMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an inbound email exchange body into a structured {@link EmailMessage}.
 *
 * <p>Accepts the following body formats:
 * <ol>
 *   <li>Already an {@link EmailMessage} — passed through unchanged.</li>
 *   <li>JSON string / byte[] — parsed via Jackson into {@link EmailMessage}.</li>
 *   <li>Raw RFC-2822 text — heuristic parsing of headers + body + quoted text.</li>
 * </ol>
 *
 * <p>After parsing:
 * <ul>
 *   <li>Exchange body is replaced with the {@link EmailMessage}.</li>
 *   <li>Header {@code correlationId} set to {@code threadId} (if non-blank) else {@code messageId}.</li>
 *   <li>Header {@code SourceType} set to {@code "email"}.</li>
 *   <li>Quoted text sections are stripped from {@code body} and stored in header
 *       {@code quotedText} so downstream processors can inspect them separately.</li>
 *   <li>Attachment names are stored in header {@code attachmentPaths}.</li>
 * </ul>
 */
@Component
public class EmailParsingProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(EmailParsingProcessor.class);

    // ── Patterns for raw RFC-2822 heuristic parsing ───────────────────────────
    /** Lines that look like RFC-2822 headers, e.g. "From: alice@example.com" */
    private static final Pattern RFC2822_HEADER = Pattern.compile(
            "^(From|To|Cc|Subject|Date|Message-ID|Thread-Index|MIME-Version|Content-Type):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    /** Quoted text: lines starting with "> " (Outlook / Gmail style) */
    private static final Pattern QUOTED_LINE = Pattern.compile("^>.*", Pattern.MULTILINE);

    /** Outlook-style "On <date>, <person> wrote:" separator */
    private static final Pattern OUTLOOK_SEPARATOR = Pattern.compile(
            "^On\\s.{5,80}wrote:\\s*$", Pattern.MULTILINE);

    /** Attachment markers in MIME bodies, e.g. "[Attachment: report.pdf]" */
    private static final Pattern ATTACHMENT_MARKER = Pattern.compile(
            "\\[Attachment:\\s*([^\\]]+)]", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public EmailParsingProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Object body = exchange.getIn().getBody();

        EmailMessage email = switch (body) {
            case EmailMessage em -> em;                           // already parsed
            case byte[] bytes    -> parseJson(new String(bytes)); // JSON bytes
            case String s when looksLikeJson(s) -> parseJson(s); // JSON string
            case String s        -> parseRawRfc2822(s);           // raw email text
            case java.io.InputStream is -> {                      // InputStreamCache / any stream
                String text = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                yield looksLikeJson(text) ? parseJson(text) : parseRawRfc2822(text);
            }
            default -> {
                log.warn("EmailParsingProcessor: unexpected body type {} — wrapping as plain text",
                        body == null ? "null" : body.getClass().getSimpleName());
                yield buildMinimal(body == null ? "" : body.toString());
            }
        };

        // ── Strip quoted sections, store separately ───────────────────────────
        String[] parts = separateQuotedText(email.body());
        String cleanBody   = parts[0];
        String quotedText  = parts[1];

        // ── Rebuild with clean body if quoted text was stripped ───────────────
        if (!quotedText.isBlank()) {
            email = new EmailMessage(
                    email.messageId(), email.threadId(), email.subject(),
                    cleanBody, email.sender(), email.timestamp(), email.attachmentPaths());
            exchange.getIn().setHeader("quotedText", quotedText);
        }

        // ── Extract attachment names from body markers ─────────────────────────
        List<String> attachments = extractAttachments(cleanBody);
        if (!attachments.isEmpty()) {
            exchange.getIn().setHeader("attachmentPaths", String.join(",", attachments));
        }

        // ── Derive correlation key ────────────────────────────────────────────
        String correlationId = email.threadId() != null && !email.threadId().isBlank()
                ? email.threadId()
                : email.messageId();

        // ── Set exchange state ────────────────────────────────────────────────
        exchange.getIn().setBody(email);
        exchange.getIn().setHeader("correlationId", correlationId);
        exchange.getIn().setHeader("SourceType",    "email");
        exchange.getIn().setHeader("PipelineId",
                "email-" + email.messageId() + "-" + exchange.getExchangeId());

        log.debug("EmailParsingProcessor: parsed messageId={} threadId={} bodyLen={}",
                email.messageId(), email.threadId(), email.body().length());
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private boolean looksLikeJson(String s) {
        String trimmed = s.stripLeading();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    /**
     * Parse a JSON string into an {@link EmailMessage}.
     * Accepts either the exact DTO shape or the synthetic dataset shape
     * (fields: from, body/content, subject, etc.).
     */
    EmailMessage parseJson(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);

        String messageId = textOrRandom(node, "messageId", "id");
        String threadId  = textOrEmpty(node, "threadId", "thread-id", "threadIndex");
        String subject   = textOrEmpty(node, "subject");
        String body      = textOrEmpty(node, "body", "content", "text");
        String sender    = textOrEmpty(node, "sender", "from");
        Instant timestamp = parseTimestamp(node, "timestamp", "receivedAt", "date");
        List<String> attachments = parseAttachmentList(node);

        return new EmailMessage(messageId, threadId, subject, body, sender, timestamp, attachments);
    }

    /**
     * Heuristic RFC-2822 parser: splits header block from body, extracts key
     * header values, and returns an {@link EmailMessage}.
     */
    EmailMessage parseRawRfc2822(String raw) {
        String[] lines = raw.split("\\r?\\n");

        String messageId = UUID.randomUUID().toString();
        String threadId  = "";
        String subject   = "";
        String sender    = "";
        Instant timestamp = Instant.now();
        StringBuilder bodyBuilder = new StringBuilder();
        boolean inBody = false;

        for (String line : lines) {
            if (!inBody) {
                if (line.isBlank()) {
                    inBody = true; // blank line separates headers from body
                    continue;
                }
                Matcher m = RFC2822_HEADER.matcher(line);
                if (m.matches()) {
                    String key   = m.group(1).toLowerCase();
                    String value = m.group(2).trim();
                    switch (key) {
                        case "message-id"    -> messageId = value.replaceAll("[<>]", "");
                        case "thread-index"  -> threadId  = value;
                        case "subject"       -> subject   = value;
                        case "from"          -> sender    = value;
                        case "date"          -> timestamp = parseRfc2822Date(value);
                    }
                }
            } else {
                bodyBuilder.append(line).append('\n');
            }
        }

        return new EmailMessage(messageId, threadId, subject,
                bodyBuilder.toString().stripTrailing(), sender, timestamp, List.of());
    }

    private EmailMessage buildMinimal(String text) {
        return new EmailMessage(UUID.randomUUID().toString(), "", "", text, "", Instant.now(), List.of());
    }

    // ── Quoted-text separation ────────────────────────────────────────────────

    /**
     * Splits body into [cleanBody, quotedText].
     * Removes Outlook-style "On ... wrote:" separators and ">" prefixed lines.
     */
    String[] separateQuotedText(String body) {
        if (body == null || body.isBlank()) return new String[]{"", ""};

        // Split at Outlook-style separator
        Matcher sep = OUTLOOK_SEPARATOR.matcher(body);
        String mainPart  = body;
        String quotePart = "";
        if (sep.find()) {
            mainPart  = body.substring(0, sep.start()).stripTrailing();
            quotePart = body.substring(sep.start()).stripLeading();
        }

        // Also strip inline ">" lines from main part
        StringBuilder clean  = new StringBuilder();
        StringBuilder quoted = new StringBuilder(quotePart);
        for (String line : mainPart.split("\\r?\\n")) {
            if (QUOTED_LINE.matcher(line).matches()) {
                quoted.append(line).append('\n');
            } else {
                clean.append(line).append('\n');
            }
        }

        return new String[]{
                clean.toString().stripTrailing(),
                quoted.toString().stripTrailing()
        };
    }

    // ── Attachment extraction ─────────────────────────────────────────────────

    private List<String> extractAttachments(String body) {
        List<String> attachments = new ArrayList<>();
        if (body == null) return attachments;
        Matcher m = ATTACHMENT_MARKER.matcher(body);
        while (m.find()) {
            attachments.add(m.group(1).trim());
        }
        return attachments;
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

    private Instant parseTimestamp(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode n = node.get(key);
            if (n != null && n.isTextual()) {
                try { return Instant.parse(n.asText()); } catch (Exception ignored) {}
            }
        }
        return Instant.now();
    }

    private List<String> parseAttachmentList(JsonNode node) {
        List<String> result = new ArrayList<>();
        JsonNode att = node.get("attachmentPaths");
        if (att == null) att = node.get("attachments");
        if (att != null && att.isArray()) {
            att.forEach(a -> result.add(a.asText()));
        }
        return result;
    }

    private Instant parseRfc2822Date(String value) {
        try {
            java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z")
                            .withLocale(java.util.Locale.ENGLISH);
            return java.time.ZonedDateTime.parse(value, fmt).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
