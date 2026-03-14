package com.aibook.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt templates from {@code classpath:prompts/} and caches them.
 *
 * <p>Templates use {@code {{variable}}} placeholders that are replaced by
 * {@link #loadAndFill(String, Map)}.
 *
 * <p>The cache is a {@link ConcurrentHashMap} keyed by the classpath path,
 * so each file is read from disk exactly once per JVM lifetime.
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Load a prompt template by its classpath path (relative to resources root).
     *
     * @param promptPath e.g. {@code "prompts/summarization/email-summary.txt"}
     * @return the raw template string
     * @throws IllegalStateException if the resource cannot be read
     */
    public String load(String promptPath) {
        return cache.computeIfAbsent(promptPath, path -> {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("PromptLoader: cached '{}' ({} chars)", path, content.length());
                return content;
            } catch (IOException e) {
                throw new IllegalStateException(
                        "PromptLoader: cannot read prompt template '" + path + "'", e);
            }
        });
    }

    /**
     * Load a prompt template and substitute {@code {{key}}} placeholders with the
     * values from {@code vars}.
     *
     * <p>Substitution is performed in a single pass over the template string.
     * Unknown placeholder keys are left unchanged so callers can detect missing vars.
     *
     * @param promptPath classpath path to the template file
     * @param vars       map of variable name → replacement value
     * @return the fully resolved prompt string
     */
    public String loadAndFill(String promptPath, Map<String, String> vars) {
        String template = load(promptPath);
        if (vars == null || vars.isEmpty()) return template;

        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}