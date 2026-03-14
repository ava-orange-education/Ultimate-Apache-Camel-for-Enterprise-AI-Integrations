package com.aibook.routes.summarization;

import com.aibook.core.dto.DocumentContent;
import com.aibook.core.dto.EmailMessage;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a structured multi-document context string from an aggregated exchange.
 *
 * <p>This processor is the first step in {@code direct:prepareForSummarization}.
 * It expects the exchange body to already be a structured thread-context string
 * (as produced by {@link ThreadAggregationStrategy#onCompletion(Exchange)}),
 * OR a single {@link EmailMessage} / {@link DocumentContent} / plain text.
 *
 * <p>It applies structural markers and metadata annotations that help the LLM
 * understand the document boundaries and content types:
 *
 * <pre>
 * ============================================================
 *  MULTI-DOCUMENT SUMMARIZATION CONTEXT
 *  Source type : thread
 *  Parts       : 3
 *  Total chars : 4521
 * ============================================================
 *
 * === EMAIL THREAD / DOCUMENT CONTEXT ===
 * ...thread content...
 *
 * ============================================================
 *  END OF CONTEXT
 * ============================================================
 * </pre>
 *
 * <p>After processing:
 * <ul>
 *   <li>Exchange body is the fully annotated context string.</li>
 *   <li>Header {@code contextBuiltAt} is set to ISO-8601 timestamp.</li>
 *   <li>Header {@code contextCharCount} is set to the character count.</li>
 * </ul>
 */
@Component
public class MultiDocContextBuilder implements Processor {

    private static final Logger log = LoggerFactory.getLogger(MultiDocContextBuilder.class);

    /** Maximum context length before truncation (to stay within LLM token limits). */
    static final int MAX_CONTEXT_CHARS = 12_000;

    /** Separator line used between structural sections. */
    private static final String SEPARATOR = "=".repeat(60);

    @Override
    public void process(Exchange exchange) {
        Object body      = exchange.getIn().getBody();
        String sourceType = exchange.getIn().getHeader("SourceType", "document", String.class);

        // Resolve the raw content string from whatever body type we receive
        String rawContent = resolveContent(body, exchange);

        // Count logical parts from the threadParts property if available
        @SuppressWarnings("unchecked")
        List<String> threadParts = exchange.getProperty(
                ThreadAggregationStrategy.PROP_THREAD_PARTS, List.class);
        int partCount = (threadParts != null) ? threadParts.size() : 1;

        // Truncate if necessary (preserve beginning, signal at end)
        String content = truncate(rawContent);

        // Build the annotated context string
        String context = buildAnnotatedContext(content, sourceType, partCount);

        // Set exchange state
        exchange.getIn().setBody(context);
        exchange.getIn().setHeader("contextCharCount",  context.length());
        exchange.getIn().setHeader("contextBuiltAt",    java.time.Instant.now().toString());
        exchange.setProperty("multiDocContext", context);

        log.debug("MultiDocContextBuilder: type={} parts={} contextLen={}",
                sourceType, partCount, context.length());
    }

    // ── Content resolution ────────────────────────────────────────────────────

    /**
     * Resolve the body into a plain string. Prioritises the already-built
     * {@code threadContext} exchange property (set by {@link ThreadAggregationStrategy}),
     * then falls back to interpreting the body directly.
     */
    String resolveContent(Object body, Exchange exchange) {
        // Prefer the structured threadContext if already built by aggregation
        String threadContext = exchange.getProperty(
                ThreadAggregationStrategy.PROP_THREAD_CONTEXT, String.class);
        if (threadContext != null && !threadContext.isBlank()) {
            return threadContext;
        }

        // Otherwise interpret the body
        return switch (body) {
            case String s -> s;
            case EmailMessage email -> formatSingleEmail(email, exchange);
            case DocumentContent doc -> formatSingleDocument(doc, exchange);
            case null -> "";
            default -> body.toString();
        };
    }

    private String formatSingleEmail(EmailMessage email, Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        sb.append("From    : ").append(email.sender()).append('\n');
        sb.append("Subject : ").append(email.subject()).append('\n');
        sb.append("Date    : ").append(email.timestamp()).append('\n');
        sb.append('\n').append(email.body());

        String quotedText = exchange.getIn().getHeader("quotedText", "", String.class);
        if (!quotedText.isBlank()) {
            sb.append("\n\n[Quoted text]\n").append(quotedText);
        }
        return sb.toString();
    }

    private String formatSingleDocument(DocumentContent doc, Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        if (!doc.fileName().isBlank()) {
            sb.append("File : ").append(doc.fileName()).append('\n');
        }
        if (!doc.contentType().isBlank()) {
            sb.append("Type : ").append(doc.contentType()).append('\n');
        }
        String headings = exchange.getIn().getHeader("sectionHeadings", "", String.class);
        if (!headings.isBlank()) {
            sb.append("Sections : ").append(headings).append('\n');
        }
        sb.append('\n').append(doc.extractedText());
        return sb.toString();
    }

    // ── Context assembly ──────────────────────────────────────────────────────

    String buildAnnotatedContext(String content, String sourceType, int partCount) {
        StringBuilder sb = new StringBuilder();

        // Header banner
        sb.append(SEPARATOR).append('\n');
        sb.append(" MULTI-DOCUMENT SUMMARIZATION CONTEXT\n");
        sb.append(" Source type : ").append(sourceType).append('\n');
        sb.append(" Parts       : ").append(partCount).append('\n');
        sb.append(" Total chars : ").append(content.length()).append('\n');
        sb.append(SEPARATOR).append('\n');
        sb.append('\n');

        // Main content
        sb.append(content);

        // Footer banner
        sb.append('\n').append('\n');
        sb.append(SEPARATOR).append('\n');
        sb.append(" END OF CONTEXT\n");
        sb.append(SEPARATOR).append('\n');

        return sb.toString();
    }

    // ── Truncation ────────────────────────────────────────────────────────────

    String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_CONTEXT_CHARS) return text;

        log.warn("MultiDocContextBuilder: context too long ({} chars), truncating to {}",
                text.length(), MAX_CONTEXT_CHARS);

        return text.substring(0, MAX_CONTEXT_CHARS) +
               "\n\n[... content truncated at " + MAX_CONTEXT_CHARS + " characters ...]";
    }
}
