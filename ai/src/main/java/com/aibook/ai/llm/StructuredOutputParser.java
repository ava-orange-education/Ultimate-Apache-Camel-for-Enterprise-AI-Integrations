package com.aibook.ai.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses structured JSON responses from LLM output into typed Java objects.
 *
 * <p>LLMs often wrap JSON in markdown code fences (e.g. <code>```json ... ```</code>).
 * {@link #parse(String, Class)} strips any such fencing before attempting Jackson
 * deserialization, so callers never need to pre-clean the LLM response themselves.
 */
@Component
public class StructuredOutputParser {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputParser.class);

    // Matches ```json … ``` or ``` … ``` fences (lazy, multi-line)
    private static final Pattern FENCED_JSON = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    // Fallback: first {...} block in the text
    private static final Pattern BARE_JSON = Pattern.compile("(\\{[\\s\\S]*})",
            Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public StructuredOutputParser() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    /**
     * Parse an LLM response string into the given target type.
     *
     * <p>Strips markdown code fences, then deserializes via Jackson.
     *
     * @param llmResponse raw text returned by the LLM
     * @param targetType  Java class to deserialize into
     * @param <T>         target type
     * @return deserialized object
     * @throws IllegalArgumentException if the JSON cannot be parsed into {@code targetType}
     */
    public <T> T parse(String llmResponse, Class<T> targetType) {
        String json = extractJson(llmResponse);
        log.debug("StructuredOutputParser: parsing {} chars as {}",
                json.length(), targetType.getSimpleName());
        try {
            return objectMapper.readValue(json, targetType);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "StructuredOutputParser: cannot parse LLM response as "
                    + targetType.getSimpleName() + ": " + e.getMessage()
                    + "\nRaw JSON: " + json, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parse an LLM response into a {@code Map<String, Object>}.
     *
     * <p>Convenience wrapper around {@link #parse(String, Class)} for callers that
     * need a loosely-typed map rather than a specific target class.
     *
     * @param llmResponse raw text returned by the LLM
     * @return key-value map of the parsed JSON object
     * @throws IllegalArgumentException if the JSON cannot be parsed into a Map
     */
    public Map<String, Object> parseToMap(String llmResponse) {
        String json = extractJson(llmResponse);
        log.debug("StructuredOutputParser.parseToMap: parsing {} chars", json.length());
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "StructuredOutputParser: cannot parse LLM response as Map: "
                    + e.getMessage() + "\nRaw JSON: " + json, e);
        }
    }

    /**
     * Extract the JSON payload from a possibly prose-wrapped LLM response.
     * Priority: (1) fenced code block, (2) first bare JSON object, (3) raw text.
     */
    String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();

        // 1. Markdown fenced block
        Matcher fenced = FENCED_JSON.matcher(trimmed);
        if (fenced.find()) {
            return fenced.group(1).strip();
        }

        // 2. Bare JSON object anywhere in the response
        Matcher bare = BARE_JSON.matcher(trimmed);
        if (bare.find()) {
            return bare.group(1).strip();
        }

        // 3. Hope the whole string is valid JSON
        return trimmed;
    }
}