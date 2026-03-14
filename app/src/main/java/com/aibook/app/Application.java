package com.aibook.app;

import com.aibook.core.config.AiPipelineProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI-Camel Project — Spring Boot entry-point.
 *
 * Scans all sub-packages across the multi-module project:
 *   com.aibook.core, com.aibook.ai, com.aibook.routes.*
 */
@SpringBootApplication(proxyBeanMethods = false)
@EnableConfigurationProperties(AiPipelineProperties.class)
@ComponentScan(basePackages = {
        "com.aibook.app",
        "com.aibook.core",
        "com.aibook.ai",
        "com.aibook.routes"
})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
