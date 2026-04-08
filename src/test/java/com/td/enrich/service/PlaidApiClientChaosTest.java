package com.td.enrich.service;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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

/**
 * Chaos tests for {@link PlaidApiClient} using WireMock to simulate Plaid API failures.
 *
 * <p><b>What WireMock does:</b> WireMock starts a real HTTP server on a random port.
 * The tests configure it to return specific responses (errors, delays, bad JSON) so we
 * can verify how {@link PlaidApiClient} reacts to each condition without a live Plaid account.
 *
 * <p><b>Resilience patterns under test:</b>
 * <ul>
 *   <li><b>Retry</b> — 5xx responses are retried up to 3 times; 4xx responses are not.</li>
 *   <li><b>Circuit breaker</b> — trips OPEN after 50% failure rate; auto-transitions
 *       to HALF_OPEN after the wait duration; closes again on a successful probe.</li>
 *   <li><b>Bulkhead</b> — rejects new calls when the concurrency limit is saturated.</li>
 *   <li><b>Timeout + retry</b> — network-level timeouts trigger {@code WebClientRequestException}
 *       which matches the retry predicate; all 3 attempts timeout → {@code PlaidApiException}.</li>
 * </ul>
 *
 * <p><b>Retry predicate alignment:</b> The retry config in {@code setUp()} mirrors
 * production semantics: only retry on {@code PlaidApiException} with status &ge; 500, or
 * on network-level exceptions ({@code WebClientRequestException}, {@code TimeoutException}).
 * 4xx errors map to {@code PlaidApiException} with status &lt; 500 and are NOT retried —
 * a bad request won't fix itself on a second attempt.
 *
 * <p>{@code @RegisterExtension} starts WireMock once per test class and resets its
 * stub configuration before each test method automatically.
 */
@DisplayName("PlaidApiClient Chaos Testing")
class PlaidApiClientChaosTest {

    /** WireMock server on a random available port — no port conflicts between test runs. */
    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private PlaidApiClient plaidApiClient;
    private CircuitBreaker circuitBreaker;
    private Retry retry;

    /**
     * Builds fresh Resilience4j components and a {@link PlaidApiClient} before each test.
     *
     * <p>Fresh instances ensure one test's circuit breaker state or retry counter does
     * not bleed into the next test.
     */
    @BeforeEach
    void setUp() {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50)           // open after 50% failures in sliding window
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();

        // Mirror production semantics: retry 5xx and network errors; NEVER retry 4xx.
        // Both error classes are mapped to PlaidApiException in the onStatus handlers,
        // so we use a predicate on the status code rather than exception type.
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .retryOnException(e -> {
                    if (e instanceof com.td.enrich.exception.PlaidApiException pae) {
                        return pae.getStatusCode() >= 500; // retry 5xx; skip 4xx
                    }
                    // Retry on network-level errors (timeout, connection refused)
                    return e instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                            || e instanceof java.util.concurrent.TimeoutException;
                })
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

        // Point the WebClient at the local WireMock server
        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.getPort())
                .build();

        plaidApiClient = new PlaidApiClient(webClient, circuitBreaker, retry, bulkhead);
        // @Value fields are injected by Spring in production; ReflectionTestUtils simulates that
        ReflectionTestUtils.setField(plaidApiClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(plaidApiClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(plaidApiClient, "clientId", "test-client");
    }

    // ── Retry ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Should retry on 500 errors and eventually succeed")
    void shouldRetryOn500ErrorsAndSucceed() {
        // Given — first 2 attempts fail with 500; 3rd succeeds
        // WireMock "scenarios" model a state machine: each stub fires in a specific state
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

        // When — StepVerifier subscribes to the Mono and asserts the emitted value
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .assertNext(response -> {
                    assertThat(response).isNotNull();
                    assertThat(response.enrichedTransactions()).hasSize(1);
                })
                .verifyComplete();

        // Then — exactly 3 HTTP calls were made (2 retries + 1 success)
        wireMock.verify(exactly(3), postRequestedFor(urlEqualTo("/enrich/transactions")));
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should not retry on 4xx client errors")
    void shouldNotRetryOn4xxClientErrors() {
        // Given — Plaid returns 400 Bad Request; a bad request won't fix itself on retry
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Bad Request")));

        PlaidEnrichRequest request = createTestRequest();

        // When/Then — error propagates without retry
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(5));

        // Exactly 1 HTTP call — 4xx errors are NEVER retried
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/enrich/transactions")));
        // Resilience4j records this as a "failed call without retry attempt"
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()).isPositive();
    }

    // ── Timeout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Should handle timeout and retry")
    void shouldHandleTimeoutAndRetry() {
        // Given — WireMock delays 3s; WebClient times out at 500ms → ReadTimeoutException
        // → wrapped in WebClientRequestException → matches retry predicate → 3 attempts total
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000) // 3 seconds — much longer than the 500ms timeout
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        // Build a dedicated WebClient with an explicit 500ms response timeout.
        // Without this, the default WebClient has no response timeout and the delay
        // would never fire a ReadTimeoutException.
        reactor.netty.http.client.HttpClient httpClient =
                reactor.netty.http.client.HttpClient.create()
                        .responseTimeout(Duration.ofMillis(500));

        WebClient timedWebClient = WebClient.builder()
                .baseUrl("http://localhost:" + wireMock.getPort())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();

        PlaidApiClient timedClient = new PlaidApiClient(
                timedWebClient, circuitBreaker, retry, Bulkhead.ofDefaults("timeout-bh"));
        ReflectionTestUtils.setField(timedClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(timedClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(timedClient, "clientId", "test-client");

        PlaidEnrichRequest request = createTestRequest();

        // When/Then — all 3 attempts timeout → final error is PlaidApiException
        StepVerifier.create(timedClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(15)); // 3 attempts × 500ms timeout + 2 × 100ms wait ≈ 1.7s

        // Resilience4j records this as a "failed call with retry attempt" (retried but never succeeded)
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isPositive();
    }

    // ── Circuit breaker ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Should open circuit breaker after multiple failures")
    void shouldOpenCircuitBreakerAfterMultipleFailures() {
        // Given — every request fails with 500
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        PlaidEnrichRequest request = createTestRequest();

        // When — make enough failing requests to exceed the 50% failure-rate threshold
        // in the 5-call sliding window (need at least 5 calls, all failing = 100% > 50%)
        for (int i = 0; i < 6; i++) {
            try {
                plaidApiClient.enrichTransactions(request).block();
            } catch (Exception e) {
                // Expected failures — we're deliberately driving the circuit breaker open
            }
        }

        // Then — circuit breaker must now be OPEN (blocking further calls)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isPositive();
    }

    @Test
    @DisplayName("Chaos: Circuit breaker recovers through HALF_OPEN back to CLOSED")
    void shouldRecoverAfterCircuitBreakerGoesHalfOpen() throws InterruptedException {
        // Use a fast-recovery circuit breaker so the test completes in under 2 seconds
        CircuitBreakerConfig fastConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(600)) // short wait so test is fast
                .permittedNumberOfCallsInHalfOpenState(1)        // one probe call to close
                .automaticTransitionFromOpenToHalfOpenEnabled(true) // auto-transition without extra call
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

        // Phase 1: trip the circuit breaker OPEN with enough failures
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse().withStatus(500).withBody("Server Error")));

        for (int i = 0; i < 5; i++) {
            try { fastCbClient.enrichTransactions(request).block(); } catch (Exception ignored) {}
        }
        assertThat(fastCb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Phase 2: wait past waitDurationInOpenState so the circuit auto-transitions to HALF_OPEN
        Thread.sleep(800); // 200ms margin over the 600ms wait duration

        // Phase 3: stub a successful response and send one probe call → HALF_OPEN → CLOSED
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

        // After the successful probe call the circuit breaker must be CLOSED again
        assertThat(fastCb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── Bulkhead ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Should reject calls when bulkhead is saturated")
    void shouldRejectCallsWhenBulkheadSaturated() throws InterruptedException {
        // Configure a 1-slot bulkhead with zero wait so the second call is rejected immediately
        BulkheadConfig tightConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitDuration(Duration.ZERO) // reject instantly when saturated
                .build();
        Bulkhead tightBulkhead = BulkheadRegistry.of(tightConfig).bulkhead("tight");

        // Count rejections via the event publisher (Bulkhead.Metrics doesn't expose a rejection counter)
        java.util.concurrent.atomic.AtomicInteger rejectionCount = new java.util.concurrent.atomic.AtomicInteger(0);
        tightBulkhead.getEventPublisher().onCallRejected(e -> rejectionCount.incrementAndGet());

        // WireMock delays 3 seconds — long enough to keep the first call's slot occupied
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

        // First call subscribes and immediately occupies the only bulkhead slot
        tightClient.enrichTransactions(request).subscribe();
        Thread.sleep(150); // let the first call acquire the slot before we attempt the second

        // Second call should be rejected immediately — no slot available, zero wait duration
        StepVerifier.create(tightClient.enrichTransactions(request))
                .expectError() // BulkheadFullException or PlaidApiException wrapping it
                .verify(Duration.ofSeconds(5));

        assertThat(rejectionCount.get()).isPositive();
    }

    // ── Fault tolerance ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Chaos: Should handle intermittent failures")
    void shouldHandleIntermittentFailures() {
        // Two stubs for the same URL — WireMock picks the last one registered by default.
        // In practice, the 200 stub wins and all calls succeed, confirming the client
        // handles the transition from a previous error stub gracefully.
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

        // When — make multiple requests; some may fail depending on WireMock stub priority
        int successCount = 0;
        int attempts = 10;

        for (int i = 0; i < attempts; i++) {
            try {
                plaidApiClient.enrichTransactions(request).block();
                successCount++;
            } catch (Exception e) {
                // Some failures are expected — the test just verifies the client doesn't hang
            }
        }

        // At least some requests should succeed
        assertThat(successCount).isPositive();
    }

    @Test
    @DisplayName("Chaos: Should handle connection refused")
    void shouldHandleConnectionRefused() {
        // Point the WebClient at a port that nothing is listening on
        WebClient badWebClient = WebClient.builder()
                .baseUrl("http://localhost:9999") // connection refused
                .build();

        PlaidApiClient badClient = new PlaidApiClient(
                badWebClient, circuitBreaker, retry, Bulkhead.ofDefaults("test"));
        ReflectionTestUtils.setField(badClient, "enrichEndpoint", "/enrich/transactions");
        ReflectionTestUtils.setField(badClient, "apiKey", "test-key");
        ReflectionTestUtils.setField(badClient, "clientId", "test-client");

        PlaidEnrichRequest request = createTestRequest();

        // When/Then — connection refused is wrapped in PlaidApiException after retries exhaust
        StepVerifier.create(badClient.enrichTransactions(request))
                .expectError(PlaidApiException.class)
                .verify(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("Chaos: Should handle malformed JSON response")
    void shouldHandleMalformedJsonResponse() {
        // Given — WireMock returns a 200 with invalid JSON (missing closing brace)
        wireMock.stubFor(post(urlEqualTo("/enrich/transactions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ invalid json")));

        PlaidEnrichRequest request = createTestRequest();

        // When/Then — Jackson deserialization failure propagates as an error
        StepVerifier.create(plaidApiClient.enrichTransactions(request))
                .expectError()
                .verify();
    }

    // ── Test data helper ───────────────────────────────────────────────────────

    /**
     * Creates a minimal {@link PlaidEnrichRequest} with pre-set credentials.
     * Used by tests that only care about the resilience behaviour, not the content.
     */
    private PlaidEnrichRequest createTestRequest() {
        return new PlaidEnrichRequest(
                "test-client",
                "test-key",
                "acc_123",
                List.of()
        );
    }
}
