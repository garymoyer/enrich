package com.plaid.enrich.service;

import com.plaid.enrich.domain.PlaidEnrichRequest;
import com.plaid.enrich.domain.PlaidEnrichResponse;
import com.plaid.enrich.exception.PlaidApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Client for Plaid Enrich API integration.
 * Features:
 * - WebClient for reactive HTTP calls
 * - Retry logic with exponential backoff
 * - Circuit breaker for fault tolerance
 * - Bulkhead for parallel request limiting
 * - Comprehensive error handling
 */
@Service
@Slf4j
public class PlaidApiClient {

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    @Value("${plaid.api.enrich-endpoint}")
    private String enrichEndpoint;

    @Value("${plaid.api.api-key}")
    private String apiKey;

    @Value("${plaid.api.client-id}")
    private String clientId;

    public PlaidApiClient(WebClient plaidWebClient,
                          CircuitBreaker plaidCircuitBreaker,
                          Retry plaidRetry,
                          Bulkhead plaidBulkhead) {
        this.webClient = plaidWebClient;
        this.circuitBreaker = plaidCircuitBreaker;
        this.retry = plaidRetry;
        this.bulkhead = plaidBulkhead;
    }

    /**
     * Enriches transactions by calling Plaid API.
     * Applies retry, circuit breaker, and bulkhead patterns.
     *
     * @param request the enrichment request with transaction data
     * @return Mono containing the Plaid API response
     * @throws PlaidApiException if the API call fails after retries
     */
    public Mono<PlaidEnrichResponse> enrichTransactions(PlaidEnrichRequest request) {
        log.info("Calling Plaid Enrich API for account: {}", request.accountId());

        // Add credentials to request
        PlaidEnrichRequest enrichedRequest = new PlaidEnrichRequest(
                clientId,
                apiKey,
                request.accountId(),
                request.transactions()
        );

        return webClient
                .post()
                .uri(enrichEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(enrichedRequest)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("Plaid API 4xx error: status={}, body={}", response.statusCode(), body);
                            return Mono.error(new PlaidApiException(
                                    "Client error from Plaid API: " + body,
                                    response.statusCode().value()
                            ));
                        }))
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("Plaid API 5xx error: status={}, body={}", response.statusCode(), body);
                            return Mono.error(new PlaidApiException(
                                    "Server error from Plaid API: " + body,
                                    response.statusCode().value()
                            ));
                        }))
                .bodyToMono(PlaidEnrichResponse.class)
                .doOnSuccess(response -> log.info("Successfully enriched {} transactions",
                        response.enrichedTransactions().size()))
                .doOnError(error -> log.error("Error calling Plaid API", error))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .onErrorMap(WebClientResponseException.class, this::mapToPlaidApiException)
                .onErrorMap(Exception.class, ex -> {
                    if (ex instanceof PlaidApiException) {
                        return ex;
                    }
                    return new PlaidApiException("Unexpected error calling Plaid API", ex);
                });
    }

    /**
     * Enriches multiple transaction batches in parallel.
     * Each batch is processed with full resilience patterns applied.
     *
     * @param requests list of enrichment requests
     * @return Flux containing all Plaid API responses
     */
    public Flux<PlaidEnrichResponse> enrichTransactionsBatch(List<PlaidEnrichRequest> requests) {
        log.info("Processing batch enrichment for {} requests", requests.size());

        return Flux.fromIterable(requests)
                .flatMap(this::enrichTransactions)
                .doOnComplete(() -> log.info("Batch enrichment completed"));
    }

    /**
     * Maps WebClient exceptions to PlaidApiException.
     */
    private PlaidApiException mapToPlaidApiException(WebClientResponseException ex) {
        return new PlaidApiException(
                "Plaid API error: " + ex.getMessage(),
                ex.getStatusCode().value(),
                extractPlaidErrorCode(ex),
                ex
        );
    }

    /**
     * Extracts Plaid-specific error code from response body if available.
     */
    private String extractPlaidErrorCode(WebClientResponseException ex) {
        try {
            String body = ex.getResponseBodyAsString();
            // Simple extraction - in production, parse JSON properly
            if (body.contains("error_code")) {
                return body.substring(
                        body.indexOf("error_code") + 13,
                        Math.min(body.indexOf("\"", body.indexOf("error_code") + 13), body.length())
                );
            }
        } catch (Exception e) {
            log.debug("Could not extract Plaid error code", e);
        }
        return null;
    }

    /**
     * Health check method to verify Plaid API connectivity.
     *
     * @return Mono<Boolean> true if Plaid API is reachable
     */
    public Mono<Boolean> healthCheck() {
        return webClient
                .get()
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }
}
