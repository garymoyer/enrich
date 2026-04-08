package com.td.enrich.config;

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
 * Configures the OpenAPI 3.0 specification that powers the Swagger UI.
 *
 * <p>Once the service is running, open {@code http://localhost:8080/swagger-ui.html}
 * in a browser to explore the API interactively — you can view request/response schemas,
 * try out live calls, and read endpoint descriptions without opening the codebase.
 * The raw OpenAPI JSON is available at {@code http://localhost:8080/api-docs}.
 *
 * <p>The {@code springdoc-openapi} library auto-discovers all {@code @RestController}
 * classes and builds most of the spec automatically. This class only adds the
 * high-level metadata (title, version, contact, servers) that springdoc can't infer
 * from the code alone.
 */
@Configuration
public class OpenApiConfig {

    /** Application name read from {@code spring.application.name} in application.yml. */
    @Value("${spring.application.name}")
    private String applicationName;

    /** HTTP port read from {@code server.port} in application.yml (default 8080). */
    @Value("${server.port:8080}")
    private String serverPort;

    /**
     * Builds the top-level {@link OpenAPI} object that springdoc uses to render
     * the Swagger UI and generate the {@code /api-docs} JSON.
     *
     * @return the configured OpenAPI metadata, registered as a Spring bean
     */
    @Bean
    public OpenAPI enrichServiceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("TD Enrich Service API")
                        .description(
                            "Transaction enrichment microservice. Accepts raw bank transaction " +
                            "descriptions and returns standardized merchant data (category, logo, " +
                            "name) sourced from the Plaid API. Includes intelligent merchant " +
                            "caching, circuit breaking, and async batch processing.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Platform Team")
                                .email("platform@example.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://example.com/license")))
                // List the environments where this API is available.
                // Swagger UI shows these in a drop-down so testers can switch between local and prod.
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort)
                                .description("Local development server"),
                        new Server()
                                .url("https://api.example.com")
                                .description("Production server")
                ));
    }
}
