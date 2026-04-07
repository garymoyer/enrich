package com.td.enrich.service;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.td.enrich.config.WebClientConfig;
import com.td.enrich.domain.PlaidEnrichRequest;
import com.td.enrich.exception.PlaidApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlaidApiClient Chaos Testing")
class PlaidApiClientChaosTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private PlaidApiClient plaidApiClient;
    private CircuitBreaker circuitBreaker;
    private Retry retry;

    @BeforeEach
    void setUp() {
        // Configure resilience4j components for chaos testing
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build();

        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .build();

        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);

        circuitBreaker = circuitBreakerRegistry.circuitBreaker("plaidApi");
        retry = retryRegistry.retry("plaidApi");
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("plaidApi");

        // Configure WebClient
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.getPort())
                .build();

        plaidApiClient = new PlaidApiClient(webClient, circuitBreaker, retry, bulkhead);
        ReflectionTestUtils.setField(plaidApiClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(plaidApiClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(plaidApiClient, "clientId", "test-client");
    }

    @Test
    @DisplayName("Chaos: Should retry on 500 errors and eventually succeed")
    void shouldRetryOn500ErrorsAndSucceed() {
        // Given - First 2 attempts fail, 3rd succeeds
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error"))
                .willSetStateTo("First Retry"));

        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("First Retry")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error"))
                .willSetStateTo("Second Retry"));

        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Second Retry")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "enriched_transactions": [{
                                        "id": "txn_001",
                                        "category": "Food & Drink",
                                        "category_id": "13005000",
                                        "merchant_name": "Starbucks",
                                        "logo_url": "https://logo.clearbit.com/starbucks.com",
                                        "website": "https://www.starbucks.com",
                                        "confidence_level": "HIGH",
                                        "enrichment_metadata": {}
                                    }],
                                    "request_id": "plaid_123"
                                }
                                """)));

        PlaidEnrichRequest request = createTestRequest();

        // When
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .assertNext(response -> {
                    assertThat(response).isNotNull();
                    assertThat(response.enrichedTransactions()).hasSize(1);
                })
                .verifyComplete();

        // Then
        wireMock.verify(exactly(3), postRequestedFor(urlEqualTo("/enrich/transactions")));
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should open circuit breaker after multiple failures")
    void shouldOpenCircuitBreakerAfterMultipleFailures() {
        // Given - All requests fail
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        PlaidEnrichRequest request = createTestRequest();

        // When - Make multiple failing requests
        for (int i = 0; i < 6; i++) {
            try {
                plaidApiClient.enrichTransactions(request).block();
            } catch (Exception e) {
                // Expected failures
            }
        }

        // Then - Circuit breaker should be open
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should handle timeout and retry")
    void shouldHandleTimeoutAndRetry() {
        // Given - Slow response
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(6000) // 6 second delay (exceeds timeout)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        PlaidEnrichRequest request = createTestRequest();

        // When/Then
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(30));

        // Verify retry attempts were made
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should handle intermittent failures")
    void shouldHandleIntermittentFailures() {
        // Given - 50% failure rate
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Error")));

        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "enriched_transactions": [],
                                    "request_id": "plaid_123"
                                }
                                """)));

        PlaidEnrichRequest request = createTestRequest();

        // When - Make multiple requests
        int successCount = 0;
        int attempts = 10;

        for (int i = 0; i < attempts; i++) {
            try {
                plaidApiClient.enrichTransactions(request).block();
                successCount++;
            } catch (Exception e) {
                // Some failures expected
            }
        }

        // Then - Should have some successes despite intermittent failures
        assertThat(successCount).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should handle connection refused")
    void shouldHandleConnectionRefused() {
        // Given - Invalid port (connection refused)
        WebClient badWebClient = WebClient.builder()
                .baseUrl("http://localhost:9999") // Non-existent port
                .build();

        PlaidApiClient badClient = new PlaidApiClient(badWebClient, circuitBreaker, retry, Bulkhead.ofDefaults("test"));
        ReflectionTestUtils.setField(badClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(badClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(badClient, "clientId", "test-client");

        PlaidEnrichRequest request = createTestRequest();

        // When/Then
        StepVerifier.create(badClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("Chaos: Should handle malformed JSON response")
    void shouldHandleMalformedJsonResponse() {
        // Given - Invalid JSON
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ invalid json")));

        PlaidEnrichRequest request = createTestRequest();

        // When/Then
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("Chaos: Should not retry on 4xx client errors")
    void shouldNotRetryOn4xxClientErrors() {
        // Given - Plaid returns 400 Bad Request
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Bad Request")));

        PlaidEnrichRequest request = createTestRequest();

        // When/Then - error propagates without retry
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(5));

        // Exactly 1 attempt — 4xx errors are not retried
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/enrich/transactions")));
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should reject calls when bulkhead is saturated")
    void shouldRejectCallsWhenBulkheadSaturated() throws InterruptedException {
        // Configure a 1-slot bulkhead with zero wait to force immediate rejection
        BulkheadConfig tightConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO)
                .build();
        Bulkhead tightBulkhead = BulkheadRegistry.of(tightConfig).bulkhead("tight");

        // Slow response holds the only slot open for the full delay
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"enriched_transactions\":[], \"request_id\":\"r1\"}")));

        PlaidApiClient tightClient = new PlaidApiClient(
                WebClient.builder().baseUrl("http://localhost:" + wireMock.getPort()).build(),
                circuitBreaker, retry, tightBulkhead);
        ReflectionTestUtils.setField(tightClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(tightClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(tightClient, "clientId", "test-client");

        PlaidEnrichRequest request = createTestRequest();

        // First request subscribes and holds the only bulkhead slot
        tightClient.enrichTransactions(request).subscribe();
        Thread.sleep(150); // Let first request acquire the slot

        // Second request should be rejected immediately (no capacity, no wait)
        StepVerifier.create(tightClient.enrichTransactions(request))
                .expectError()
                .verify(Duration.ofSeconds(5));

        assertThat(tightBulkhead.getMetrics().getNumberOfRejectedCalls()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Circuit breaker recovers through HALF_OPEN back to CLOSED")
    void shouldRecoverAfterCircuitBreakerGoesHalfOpen() throws InterruptedException {
        // Fast-recovery circuit breaker so the test runs in under 2 seconds
        CircuitBreakerConfig fastConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(600))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreaker fastCb = CircuitBreakerRegistry.of(fastConfig)
                .circuitBreaker("halfOpenTest");

        PlaidApiClient fastCbClient = new PlaidApiClient(
                WebClient.builder().baseUrl("http://localhost:" + wireMock.getPort()).build(),
                fastCb, retry, Bulkhead.ofDefaults("halfOpenBh"));
        ReflectionTestUtils.setField(fastCbClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(fastCbClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(fastCbClient, "clientId", "test-client");

        PlaidEnrichRequest request = createTestRequest();

        // Phase 1: fail enough calls to trip the circuit breaker OPEN
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));

        for (int i = 0; i < 5; i++) {
            try { fastCbClient.enrichTransactions(request).block(); } catch (Exception ignored) {}
        }
        assertThat(fastCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: wait past waitDurationInOpenState so auto-transition fires to HALF_OPEN
        Thread.sleep(800);

        // Phase 3: one successful response pushes HALF_OPEN → CLOSED
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "enriched_transactions": [{
                                        "id": "txn_recover",
                                        "category": "Food",
                                        "category_id": "1",
                                        "merchant_name": "Recovery Cafe",
                                        "logo_url": null,
                                        "website": null,
                                        "confidence_level": "HIGH",
                                        "enrichment_metadata": {}
                                    }],
                                    "request_id": "req_recover"
                                }
                                """)));

        StepVerifier.create(fastCbClient.enrichTransactions(request))
                .assertNext(r -> assertThat(r.enrichedTransactions()).hasSize(1))
                .verifyComplete();

        // Circuit breaker should now be CLOSED again
        assertThat(fastCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private PlaidEnrichRequest createTestRequest() {
        return new PlaidEnrichRequest(
                "test-client",
                "test-key",
                "acc_123",
                List.of()
        );
    }
}
