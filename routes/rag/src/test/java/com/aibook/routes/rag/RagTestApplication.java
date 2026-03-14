package com.aibook.routes.rag;

import com.aibook.core.config.AiPipelineProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Minimal Spring Boot test application for the rag module's integration tests.
 * Beans are loaded explicitly via @SpringBootTest(classes=...).
 */
@SpringBootApplication(scanBasePackages = {})
@EnableConfigurationProperties(AiPipelineProperties.class)
public class RagTestApplication {
}