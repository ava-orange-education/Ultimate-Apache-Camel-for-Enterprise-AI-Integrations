package com.aibook.routes.summarization;

import com.aibook.core.config.AiPipelineProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Minimal Spring Boot test application for the summarization module's integration tests.
 * Beans are loaded explicitly via @SpringBootTest(classes=...).
 *
 * <p>Excludes the Camel REST/OpenAPI auto-configuration to prevent an auto-generated
 * REST API-doc route (route1) from failing when camel-openapi-java is not on the
 * test classpath.
 */
@SpringBootApplication(
    scanBasePackages = {},
    exclude = {
        org.apache.camel.component.rest.openapi.springboot.RestOpenApiComponentAutoConfiguration.class
    }
)
@EnableConfigurationProperties(AiPipelineProperties.class)
public class SummarizationTestApplication {
}