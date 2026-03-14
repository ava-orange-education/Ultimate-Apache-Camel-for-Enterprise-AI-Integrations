package com.aibook.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JavaTimeModule} globally so that {@code java.time.Instant}
 * (and other JSR-310 types) serialise correctly in all contexts — including the
 * Camel file-write marshaller that creates a bare {@link ObjectMapper} without
 * Spring Boot's auto-configuration applied.
 */
@Configuration
public class JacksonConfig {

    /**
     * Customises the primary Spring Boot {@link ObjectMapper} to disable
     * timestamp serialisation and register the JSR-310 module.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310Customizer() {
        return builder -> builder
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
