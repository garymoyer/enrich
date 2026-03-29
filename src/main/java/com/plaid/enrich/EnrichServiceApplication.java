package com.plaid.enrich;

import com.plaid.enrich.config.EnrichCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Main Spring Boot application class for Plaid Enrich Service.
 *
 * Production-ready microservice featuring:
 * - Plaid API integration with WebClient
 * - Resilience4j patterns (retry, circuit breaker, bulkhead)
 * - Azure SQL Database persistence
 * - Comprehensive testing (90%+ coverage)
 * - OpenAPI/Swagger documentation
 * - Spring Boot Actuator monitoring
 * - Azure App Service deployment ready
 */
@SpringBootApplication
@EnableConfigurationProperties(EnrichCacheProperties.class)
public class EnrichServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrichServiceApplication.class, args);
    }
}
