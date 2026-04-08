package com.td.enrich.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires up the three Resilience4j fault-tolerance patterns that protect
 * every call to the Plaid API.
 *
 * <p>The numeric settings for each pattern come from the {@code resilience4j.*}
 * section of {@code application.yml}. This class doesn't duplicate those numbers —
 * it reads them from the pre-configured registries that Resilience4j's Spring Boot
 * auto-configuration builds automatically. The only work done here is attaching
 * event listeners for logging and creating the named instances.
 *
 * <p><b>How the three patterns interact during a Plaid call:</b>
 * <ol>
 *   <li><b>Bulkhead</b> is checked first. If the maximum number of concurrent Plaid
 *       calls is already in progress, the new call is rejected immediately with a
 *       {@code BulkheadFullException}. This prevents one slow upstream from consuming
 *       all available threads.</li>
 *   <li><b>Circuit Breaker</b> is checked next. If the breaker is OPEN (too many
 *       recent failures), the call is short-circuited and never sent to Plaid.</li>
 *   <li><b>Retry</b> wraps the actual HTTP call. If Plaid returns a 5xx or a network
 *       error, the retry logic waits a short time and tries again (up to 3 times)
 *       before propagating the failure.</li>
 * </ol>
 *
 * <p>All three beans are named {@code "plaidApi"} to match the instance names in
 * {@code application.yml} under {@code resilience4j.circuitbreaker.instances.plaidApi},
 * {@code resilience4j.retry.instances.plaidApi}, and
 * {@code resilience4j.bulkhead.instances.plaidApi}.
 */
@Configuration
@Slf4j
public class ResilienceConfig {

    /** The Resilience4j instance name — must match the key in application.yml. */
    private static final String PLAID_API_INSTANCE = "plaidApi";

    /**
     * Creates the circuit breaker that guards Plaid API calls.
     *
     * <p>A circuit breaker works like a household fuse: it tracks the recent failure
     * rate and "trips" (opens) when failures exceed the configured threshold (default
     * 50%). While OPEN, all calls fail fast without touching Plaid. After the cool-down
     * window (default 10 s), the breaker moves to HALF_OPEN and allows a small number
     * of probe requests through. If those succeed, it closes again.
     *
     * <p>States: {@code CLOSED} (normal) → {@code OPEN} (tripped) → {@code HALF_OPEN}
     * (probing) → {@code CLOSED} (recovered).
     *
     * @param circuitBreakerRegistry the auto-configured registry; holds the settings
     *                               from {@code application.yml}
     * @return the configured circuit breaker instance, registered as a Spring bean
     */
    @Bean
    public CircuitBreaker plaidCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PLAID_API_INSTANCE);

        // Attach event listeners so every state transition and failure is visible in logs.
        // These are observability hooks only — they don't change the breaker's behavior.
        circuitBreaker.getEventPublisher()
                .onSuccess(event ->
                        log.debug("Circuit breaker [{}]: call succeeded", PLAID_API_INSTANCE))
                .onError(event ->
                        log.warn("Circuit breaker [{}]: call failed — {}",
                                PLAID_API_INSTANCE, event.getThrowable().getMessage()))
                .onStateTransition(event ->
                        // This is the most important event: tells us when the breaker trips or recovers
                        log.warn("Circuit breaker [{}]: state changed {} → {}",
                                PLAID_API_INSTANCE,
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState()))
                .onSlowCallRateExceeded(event ->
                        log.warn("Circuit breaker [{}]: slow call rate exceeded — {}",
                                PLAID_API_INSTANCE, event))
                .onFailureRateExceeded(event ->
                        log.error("Circuit breaker [{}]: failure rate exceeded threshold — {}",
                                PLAID_API_INSTANCE, event));

        return circuitBreaker;
    }

    /**
     * Creates the retry policy applied to every Plaid API call.
     *
     * <p>When a Plaid call fails with a retryable error (5xx, network timeout), the
     * retry logic pauses for a configurable wait time and tries again, up to 3 attempts
     * by default. 4xx errors are not retried — a bad request won't fix itself on a
     * second attempt.
     *
     * <p>The wait duration and attempt count are configured in {@code application.yml}
     * under {@code resilience4j.retry.instances.plaidApi}.
     *
     * @param retryRegistry the auto-configured registry holding settings from application.yml
     * @return the configured retry policy, registered as a Spring bean
     */
    @Bean
    public Retry plaidRetry(RetryRegistry retryRegistry) {
        Retry retry = retryRegistry.retry(PLAID_API_INSTANCE);

        retry.getEventPublisher()
                .onRetry(event ->
                        log.warn("Retry [{}]: attempt #{} after error — {}",
                                PLAID_API_INSTANCE,
                                event.getNumberOfRetryAttempts(),
                                event.getLastThrowable().getMessage()))
                .onSuccess(event ->
                        log.debug("Retry [{}]: succeeded after {} attempt(s)",
                                PLAID_API_INSTANCE, event.getNumberOfRetryAttempts()))
                .onError(event ->
                        log.error("Retry [{}]: exhausted {} attempts — giving up: {}",
                                PLAID_API_INSTANCE,
                                event.getNumberOfRetryAttempts(),
                                event.getLastThrowable().getMessage()));

        return retry;
    }

    /**
     * Creates the bulkhead that limits concurrent calls to the Plaid API.
     *
     * <p>A bulkhead isolates one dependency (Plaid) from consuming all of the service's
     * resources. If the configured maximum number of concurrent Plaid calls is already
     * in flight, new calls are rejected immediately rather than piling up. This keeps
     * the service responsive to other requests even when Plaid is slow.
     *
     * <p>The concurrent-call limit is configured in {@code application.yml} under
     * {@code resilience4j.bulkhead.instances.plaidApi.max-concurrent-calls}.
     *
     * @param bulkheadRegistry the auto-configured registry holding settings from application.yml
     * @return the configured bulkhead instance, registered as a Spring bean
     */
    @Bean
    public Bulkhead plaidBulkhead(BulkheadRegistry bulkheadRegistry) {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(PLAID_API_INSTANCE);

        bulkhead.getEventPublisher()
                .onCallPermitted(event ->
                        log.debug("Bulkhead [{}]: call permitted — {}/{} slots used",
                                PLAID_API_INSTANCE,
                                bulkhead.getMetrics().getMaxAllowedConcurrentCalls()
                                        - bulkhead.getMetrics().getAvailableConcurrentCalls(),
                                bulkhead.getMetrics().getMaxAllowedConcurrentCalls()))
                .onCallRejected(event ->
                        // This is important: a rejection means Plaid is under heavy load
                        log.warn("Bulkhead [{}]: call rejected — all {} slots occupied",
                                PLAID_API_INSTANCE,
                                bulkhead.getMetrics().getMaxAllowedConcurrentCalls()))
                .onCallFinished(event ->
                        log.debug("Bulkhead [{}]: call finished", PLAID_API_INSTANCE));

        return bulkhead;
    }
}
