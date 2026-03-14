package com.aibook.routes.summarization;

import com.aibook.ai.llm.PromptLoader;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Assembles the final LLM prompt for the summarization step.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Select the correct prompt template based on the {@code SourceType} header:
 *       {@code "email"} → {@code email-summary.txt},
 *       {@code "thread"} → {@code thread-summary.txt},
 *       otherwise → {@code document-summary.txt}.</li>
 *   <li>Load the template via {@link PromptLoader} (cached, classpath).</li>
 *   <li>Fill the {@code {{context}}} placeholder with the annotated context string
 *       built by {@link MultiDocContextBuilder}.</li>
 *   <li>Split the filled template into system and user parts on the
 *       {@code ---USER---} separator line (if present); otherwise the whole
 *       template is used as the user message with a fixed system prompt.</li>
 *   <li>Set exchange headers:
 *     <ul>
 *       <li>{@code promptSystemMessage} — the system-role prompt string</li>
 *       <li>{@code promptUserMessage}   — the user-role prompt string</li>
 *       <li>{@code PromptTemplate}      — classpath path used (for audit)</li>
 *     </ul>
 *   </li>
 *   <li>The exchange body is replaced with the assembled user message so that
 *       it can be sent directly to the LLM call processor.</li>
 * </ol>
 *
 * <h3>Prompt template format</h3>
 * <pre>
 * SYSTEM: You are an expert email analyst...
 * ---USER---
 * Summarize this email thread...
 * Thread: {{context}}
 * </pre>
 * If no {@code ---USER---} separator is present the entire template is the user message
 * and the default system prompt below is used.
 */
@Component
public class PromptAssembler implements Processor {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    /** Separator between system and user sections inside a prompt template file. */
    static final String USER_SEPARATOR = "---USER---";

    /** Fallback system prompt when the template has no SYSTEM section. */
    static final String DEFAULT_SYSTEM_PROMPT =
            "You are an expert AI analyst specializing in document and email summarization. " +
            "Return a concise, accurate summary as valid JSON.";

    // ── Prompt template paths (classpath, under app/src/main/resources) ───────
    private static final String EMAIL_PROMPT    = "prompts/summarization/email-summary.txt";
    private static final String THREAD_PROMPT   = "prompts/summarization/thread-summary.txt";
    private static final String DOCUMENT_PROMPT = "prompts/summarization/document-summary.txt";

    private final PromptLoader promptLoader;

    public PromptAssembler(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    @Override
    public void process(Exchange exchange) {
        String sourceType = exchange.getIn().getHeader("SourceType", "document", String.class);
        String context    = exchange.getIn().getBody(String.class);
        if (context == null) context = "";

        // ── Select template ───────────────────────────────────────────────────
        String templatePath = switch (sourceType.toLowerCase()) {
            case "email"  -> EMAIL_PROMPT;
            case "thread" -> THREAD_PROMPT;
            default       -> DOCUMENT_PROMPT;
        };

        // ── Load + fill template ──────────────────────────────────────────────
        String filledTemplate;
        try {
            filledTemplate = promptLoader.loadAndFill(
                    templatePath,
                    Map.of("context", context));
        } catch (IllegalStateException e) {
            // Template not found — fall back to inline minimal prompt
            log.warn("PromptAssembler: template '{}' not found, using inline fallback. {}",
                    templatePath, e.getMessage());
            filledTemplate = buildFallbackPrompt(sourceType, context);
            templatePath   = "INLINE_FALLBACK";
        }

        // ── Split into system / user sections ─────────────────────────────────
        String[] sections = splitTemplate(filledTemplate);
        String systemMessage = sections[0];
        String userMessage   = sections[1];

        // ── Set exchange state ─────────────────────────────────────────────────
        exchange.getIn().setBody(userMessage);
        exchange.getIn().setHeader("promptSystemMessage", systemMessage);
        exchange.getIn().setHeader("promptUserMessage",   userMessage);
        exchange.getIn().setHeader("PromptTemplate",      templatePath);

        log.debug("PromptAssembler: sourceType={} template={} systemLen={} userLen={}",
                sourceType, templatePath, systemMessage.length(), userMessage.length());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Split a filled template into [systemMessage, userMessage] on the
     * {@link #USER_SEPARATOR} line. If no separator is found the system
     * message is the {@link #DEFAULT_SYSTEM_PROMPT} and the user message
     * is the full template.
     */
    String[] splitTemplate(String template) {
        int idx = template.indexOf(USER_SEPARATOR);
        if (idx < 0) {
            return new String[]{ DEFAULT_SYSTEM_PROMPT, template.strip() };
        }

        String systemPart = template.substring(0, idx).strip();
        // Remove leading "SYSTEM:" prefix if present
        if (systemPart.startsWith("SYSTEM:")) {
            systemPart = systemPart.substring("SYSTEM:".length()).strip();
        }
        if (systemPart.isBlank()) {
            systemPart = DEFAULT_SYSTEM_PROMPT;
        }

        String userPart = template.substring(idx + USER_SEPARATOR.length()).strip();
        return new String[]{ systemPart, userPart };
    }

    private String buildFallbackPrompt(String sourceType, String context) {
        return String.format(
                "Summarize the following %s content. " +
                "Return a JSON object with fields: summary, keyPoints, sentiment, actionItems.\n\n" +
                "Content:\n%s",
                sourceType, context);
    }
}
