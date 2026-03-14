package com.aibook.core.processors;

import com.aibook.core.dto.DocumentContent;
import com.aibook.core.dto.EmailMessage;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Normalizes inbound content (EmailMessage, DocumentContent, or raw String).
 *
 * <ul>
 *   <li>Strips MIME noise: HTML tags, base64 attachment markers, X-headers</li>
 *   <li>Normalizes whitespace: collapses runs of spaces/tabs/newlines</li>
 *   <li>Unifies timestamps: coerces common date formats to ISO-8601 UTC strings</li>
 *   <li>Sets exchange headers: {@code contentType} and {@code normalizedAt}</li>
 * </ul>
 */
@Component
public class ContentNormalizer implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ContentNormalizer.class);

    // ── MIME / HTML noise patterns ────────────────────────────────────────────
    private static final Pattern HTML_TAGS       = Pattern.compile("<[^>]{1,500}>", Pattern.DOTALL);
    private static final Pattern HTML_ENTITIES   = Pattern.compile("&(?:[a-z]{2,8}|#\\d{1,5});");
    private static final Pattern BASE64_BLOCK    = Pattern.compile(
            "(?:Content-Transfer-Encoding:\\s*base64.*?(?=\\n--|\\.\\s*$))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MIME_HEADER     = Pattern.compile(
            "^(Content-Type|Content-Transfer-Encoding|MIME-Version|X-[\\w-]+):.*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern BOUNDARY_MARKER = Pattern.compile("^--[\\w+/=-]{10,}.*$",
            Pattern.MULTILINE);

    // ── Whitespace patterns ───────────────────────────────────────────────────
    private static final Pattern MULTI_BLANK_LINE = Pattern.compile("(\\n\\s*){3,}");
    private static final Pattern TRAILING_SPACES  = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);
    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[ \\t]{2,}");

    // ── Timestamp patterns (common non-ISO formats) ───────────────────────────
    // e.g. "Mon, 02 Mar 2026 09:15:00 +0000" (RFC 2822)
    private static final Pattern RFC2822_DATE = Pattern.compile(
            "\\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\\s+\\d{1,2}\\s+\\w{3}\\s+\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}\\s+[+-]\\d{4}\\b");
    private static final DateTimeFormatter RFC2822_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z")
                    .withLocale(java.util.Locale.ENGLISH);

    @Override
    public void process(Exchange exchange) {
        Object body = exchange.getIn().getBody();

        String rawText;
        String contentType;

        switch (body) {
            case EmailMessage email -> {
                rawText     = buildEmailText(email);
                contentType = "email";
                // Propagate original MIME type from the message if available
                exchange.getIn().setHeader("originalContentType", "message/rfc822");
            }
            case DocumentContent doc -> {
                rawText     = doc.extractedText();
                contentType = doc.contentType();
            }
            case String s -> {
                rawText     = s;
                contentType = exchange.getIn().getHeader("contentType", "text/plain", String.class);
            }
            case null -> {
                log.warn("ContentNormalizer: null body in exchange {}", exchange.getExchangeId());
                rawText     = "";
                contentType = "text/plain";
            }
            default -> {
                log.warn("ContentNormalizer: unrecognised body type {} in exchange {}",
                        body.getClass().getName(), exchange.getExchangeId());
                rawText     = body.toString();
                contentType = "text/plain";
            }
        }

        String normalized = normalize(rawText);

        exchange.getIn().setBody(normalized);
        exchange.getIn().setHeader("contentType",   contentType);
        exchange.getIn().setHeader("normalizedAt",  Instant.now().toString());

        log.debug("ContentNormalizer: exchange={} contentType={} originalLen={} normalizedLen={}",
                exchange.getExchangeId(), contentType, rawText.length(), normalized.length());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildEmailText(EmailMessage email) {
        return "Subject: " + email.subject() + "\n"
             + "From: "    + email.sender()  + "\n"
             + "Date: "    + email.timestamp() + "\n"
             + "\n"
             + email.body();
    }

    String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String text = raw;

        // 1. Strip MIME structure noise
        text = BASE64_BLOCK.matcher(text).replaceAll("");
        text = BOUNDARY_MARKER.matcher(text).replaceAll("");
        text = MIME_HEADER.matcher(text).replaceAll("");

        // 2. Strip HTML
        text = HTML_TAGS.matcher(text).replaceAll(" ");
        text = HTML_ENTITIES.matcher(text).replaceAll(" ");

        // 3. Unify timestamps (RFC 2822 → ISO-8601)
        text = unifyTimestamps(text);

        // 4. Normalize whitespace
        text = TRAILING_SPACES.matcher(text).replaceAll("");
        text = INLINE_WHITESPACE.matcher(text).replaceAll(" ");
        text = MULTI_BLANK_LINE.matcher(text).replaceAll("\n\n");

        return text.strip();
    }

    private String unifyTimestamps(String text) {
        var matcher = RFC2822_DATE.matcher(text);
        var sb = new StringBuilder();
        while (matcher.find()) {
            String original = matcher.group();
            String replacement = tryParseRfc2822(original);
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String tryParseRfc2822(String dateStr) {
        try {
            // Try with day-of-week prefix, then without
            for (String fmt : new String[]{
                    "EEE, d MMM yyyy HH:mm:ss Z",
                    "d MMM yyyy HH:mm:ss Z"}) {
                try {
                    var formatter = DateTimeFormatter.ofPattern(fmt)
                            .withLocale(java.util.Locale.ENGLISH)
                            .withZone(java.time.ZoneOffset.UTC);
                    Instant instant = Instant.from(formatter.parse(dateStr.trim()));
                    return instant.toString();   // ISO-8601 UTC
                } catch (DateTimeParseException ignored) { /* try next */ }
            }
        } catch (Exception e) {
            log.debug("ContentNormalizer: could not parse date '{}': {}", dateStr, e.getMessage());
        }
        return dateStr; // leave as-is if parsing fails
    }
}