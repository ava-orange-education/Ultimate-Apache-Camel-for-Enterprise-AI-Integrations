package com.aibook.routes.graph;

import com.aibook.core.config.AiPipelineProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Minimal Spring Boot test application for the graph module's integration tests.
 * Uses default SpringBootApplication scan limited to this test class's package only —
 * main-source route beans are loaded explicitly via @SpringBootTest(classes=...).
 */
@SpringBootApplication(scanBasePackages = {})
@EnableConfigurationProperties(AiPipelineProperties.class)
public class GraphTestApplication {
}