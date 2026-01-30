package com.plaid.enrich.config;

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
 * Configuration for Resilience4j patterns.
 * Provides access to circuit breaker, retry, and bulkhead instances
 * and configures event listeners for monitoring.
 */
@Configuration
@Slf4j
public class ResilienceConfig {

    private static final String PLAID_API_INSTANCE = "plaidApi";

    /**
     * Configures circuit breaker with event listeners for monitoring.
     */
    @Bean
    public CircuitBreaker plaidCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PLAID_API_INSTANCE);

        // Event listeners for monitoring
        circuitBreaker.getEventPublisher()
                .onSuccess(event -> log.debug("Circuit breaker success: {}", event))
                .onError(event -> log.warn("Circuit breaker error: {}", event))
                .onStateTransition(event -> log.warn("Circuit breaker state transition: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onSlowCallRateExceeded(event -> log.warn("Circuit breaker slow call rate exceeded: {}", event))
                .onFailureRateExceeded(event -> log.error("Circuit breaker failure rate exceeded: {}", event));

        return circuitBreaker;
    }

    /**
     * Configures retry with event listeners for monitoring.
     */
    @Bean
    public Retry plaidRetry(RetryRegistry retryRegistry) {
        Retry retry = retryRegistry.retry(PLAID_API_INSTANCE);

        // Event listeners for monitoring
        retry.getEventPublisher()
                .onRetry(event -> log.warn("Retry attempt #{}: {}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()))
                .onSuccess(event -> log.debug("Retry succeeded after {} attempts",
                        event.getNumberOfRetryAttempts()))
                .onError(event -> log.error("Retry failed after {} attempts: {}",
                        event.getNumberOfRetryAttempts(),
                        event.getLastThrowable().getMessage()));

        return retry;
    }

    /**
     * Configures bulkhead for parallel request limiting.
     */
    @Bean
    public Bulkhead plaidBulkhead(BulkheadRegistry bulkheadRegistry) {
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(PLAID_API_INSTANCE);

        // Event listeners for monitoring
        bulkhead.getEventPublisher()
                .onCallPermitted(event -> log.debug("Bulkhead call permitted: available={}/{}",
                        bulkhead.getMetrics().getAvailableConcurrentCalls(),
                        bulkhead.getMetrics().getMaxAllowedConcurrentCalls()))
                .onCallRejected(event -> log.warn("Bulkhead call rejected: available={}/{}",
                        bulkhead.getMetrics().getAvailableConcurrentCalls(),
                        bulkhead.getMetrics().getMaxAllowedConcurrentCalls()))
                .onCallFinished(event -> log.debug("Bulkhead call finished: duration={}ms",
                        event.getCallDuration()));

        return bulkhead;
    }
}
