package com.aibook.routes.summarization;

import com.aibook.core.dto.DocumentContent;
import com.aibook.core.dto.EmailMessage;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Camel {@link AggregationStrategy} for the email/document thread correlation step.
 *
 * <p>Each call to {@link #aggregate(Exchange, Exchange)} appends the normalized body
 * text of the incoming exchange to a growing {@code List<String>} stored in the
 * aggregated exchange's {@code threadParts} property.
 *
 * <p>When aggregation is complete (timeout or size reached) the list is formatted
 * into a structured thread-context string that downstream processors can use
 * directly as LLM context:
 *
 * <pre>
 * === EMAIL THREAD / DOCUMENT CONTEXT ===
 * Total parts: 3
 *
 * [Part 1 of 3]
 * From: alice@example.com | Subject: Budget Review
 * ---
 * Hi Bob, please send the Q4 numbers...
 *
 * [Part 2 of 3]
 * ...
 * </pre>
 *
 * <p>Usage inside a Camel route:
 * <pre>{@code
 * aggregate(header("correlationId"), new ThreadAggregationStrategy())
 *     .completionTimeout(60_000)
 *     .completionSize(10)
 *     .to("direct:prepareForSummarization");
 * }</pre>
 */
public class ThreadAggregationStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ThreadAggregationStrategy.class);

    /** Exchange property key for the growing list of thread parts. */
    static final String PROP_THREAD_PARTS = "threadParts";

    /** Exchange property key for the structured thread context string (set on completion). */
    static final String PROP_THREAD_CONTEXT = "threadContext";

    @Override
    public Exchange aggregate(Exchange oldExchange, Exchange newExchange) {
        // First message in the aggregation group: oldExchange is null
        if (oldExchange == null) {
            List<String> parts = new ArrayList<>();
            parts.add(extractText(newExchange));
            newExchange.setProperty(PROP_THREAD_PARTS, parts);
            log.debug("ThreadAggregationStrategy: started new group correlationId={}",
                    newExchange.getIn().getHeader("correlationId"));
            return newExchange;
        }

        // Subsequent messages: append to existing list
        @SuppressWarnings("unchecked")
        List<String> parts = oldExchange.getProperty(PROP_THREAD_PARTS, List.class);
        if (parts == null) {
            parts = new ArrayList<>();
            oldExchange.setProperty(PROP_THREAD_PARTS, parts);
        }

        String newText = extractText(newExchange);
        parts.add(newText);

        log.debug("ThreadAggregationStrategy: aggregated part #{} for correlationId={}",
                parts.size(),
                oldExchange.getIn().getHeader("correlationId"));

        // Merge headers: preserve correlationId, SourceType; upgrade to "thread" if mixed
        String existingType = oldExchange.getIn().getHeader("SourceType", "email", String.class);
        String newType      = newExchange.getIn().getHeader("SourceType", "email", String.class);
        if (!existingType.equals(newType)) {
            oldExchange.getIn().setHeader("SourceType", "thread");
        }

        return oldExchange;
    }

    /**
     * Called by Camel when the aggregation group is complete (timeout or size).
     * Converts the {@code threadParts} list into a structured context string
     * and sets it as both the exchange body and the {@code threadContext} property.
     *
     * <p>Camel invokes this via the {@code AggregationStrategy} SPI when
     * {@code .completionTimeout()} or {@code .completionSize()} fires.
     */
    @Override
    public void onCompletion(Exchange exchange) {
        @SuppressWarnings("unchecked")
        List<String> parts = exchange.getProperty(PROP_THREAD_PARTS, List.class);
        if (parts == null || parts.isEmpty()) {
            log.warn("ThreadAggregationStrategy.onCompletion: no parts found in exchange {}",
                    exchange.getExchangeId());
            exchange.getIn().setBody("");
            return;
        }

        String threadContext = buildThreadContext(parts);
        exchange.getIn().setBody(threadContext);
        exchange.setProperty(PROP_THREAD_CONTEXT, threadContext);

        // Set source type to "thread" if more than one part
        if (parts.size() > 1) {
            exchange.getIn().setHeader("SourceType", "thread");
        }

        log.info("ThreadAggregationStrategy: completed {} parts → {} chars for correlationId={}",
                parts.size(), threadContext.length(),
                exchange.getIn().getHeader("correlationId"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extract the normalized body text from an exchange.
     * Handles {@link EmailMessage}, {@link DocumentContent}, and plain {@link String}.
     */
    String extractText(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        return switch (body) {
            case EmailMessage email -> formatEmailPart(email, exchange);
            case DocumentContent doc -> formatDocumentPart(doc, exchange);
            case String s -> s;
            case null -> "";
            default -> body.toString();
        };
    }

    private String formatEmailPart(EmailMessage email, Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(email.sender());
        if (!email.subject().isBlank()) sb.append(" | Subject: ").append(email.subject());
        if (email.timestamp() != null) sb.append(" | Date: ").append(email.timestamp());
        sb.append('\n');

        // Include quoted text in the part if present
        String quotedText = exchange.getIn().getHeader("quotedText", "", String.class);
        sb.append(email.body());
        if (!quotedText.isBlank()) {
            sb.append("\n\n[Quoted text]\n").append(quotedText);
        }
        return sb.toString();
    }

    private String formatDocumentPart(DocumentContent doc, Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        if (!doc.fileName().isBlank()) sb.append("File: ").append(doc.fileName()).append('\n');
        if (!doc.contentType().isBlank()) sb.append("Type: ").append(doc.contentType()).append('\n');
        String headings = exchange.getIn().getHeader("sectionHeadings", "", String.class);
        if (!headings.isBlank()) sb.append("Sections: ").append(headings).append('\n');
        sb.append("---\n").append(doc.extractedText());
        return sb.toString();
    }

    /**
     * Build the structured thread context string from the list of parts.
     * This is what gets sent to the LLM prompt as {@code {{context}}}.
     */
    String buildThreadContext(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== EMAIL THREAD / DOCUMENT CONTEXT ===\n");
        sb.append("Total parts: ").append(parts.size()).append("\n\n");

        for (int i = 0; i < parts.size(); i++) {
            sb.append("[Part ").append(i + 1).append(" of ").append(parts.size()).append("]\n");
            sb.append(parts.get(i));
            if (i < parts.size() - 1) {
                sb.append("\n\n---\n\n");
            }
        }
        return sb.toString();
    }
}
