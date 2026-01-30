package com.plaid.enrich.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for OpenAPI/Swagger documentation.
 * Provides comprehensive API documentation accessible via Swagger UI.
 */
@Configuration
public class OpenApiConfig {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${server.port:8080}")
    private String serverPort;

    /**
     * Configures OpenAPI specification with metadata and servers.
     */
    @Bean
    public OpenAPI enrichServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Plaid Enrich Service API")
                        .description("Production-ready microservice for Plaid transaction enrichment. " +
                                    "Features resilient API integration, GUID-based request tracking, " +
                                    "and comprehensive persistence.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Platform Team")
                                .email("platform@example.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://example.com/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Development server"),
                        new Server()
                                .url("https://api.example.com")
                                .description("Production server")
                ));
    }
}
