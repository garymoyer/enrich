package com.td.enrich.service;

import com.td.enrich.domain.PlaidEnrichRequest;
import com.td.enrich.domain.PlaidEnrichResponse;
import com.td.enrich.exception.PlaidApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Reactive HTTP client for the Plaid Enrich API.
 *
 * <p>This class has one job: send a request to Plaid and return a
 * {@link PlaidEnrichResponse}. Everything else — caching, persistence, response
 * mapping — is handled by {@link EnrichmentService} and {@link EnrichmentQueueProcessor}.
 *
 * <p><b>Resilience patterns applied to every call (innermost → outermost):</b>
 * <ol>
 *   <li><b>Retry</b> — wraps the raw HTTP call. If Plaid returns a 5xx or a network
 *       error, it waits briefly and tries again (up to 3 times). 4xx errors are
 *       not retried.</li>
 *   <li><b>Circuit Breaker</b> — wraps the retry chain. If the recent failure rate
 *       exceeds 50%, it stops sending requests to Plaid entirely for a cool-down
 *       period. During that period all calls fail fast with a
 *       {@code CallNotPermittedException}.</li>
 *   <li><b>Bulkhead</b> — outermost wrapper. If too many Plaid calls are already
 *       in flight, new ones are rejected immediately to protect thread and connection
 *       resources.</li>
 * </ol>
 *
 * <p>The operators are applied using Reactor's {@code transformDeferred} which means
 * they are re-evaluated for every subscriber (every call to {@code subscribe()} or
 * {@code block()}). This is important for the circuit breaker and bulkhead, which
 * need to check state at subscription time, not at assembly time.
 *
 * <p><b>Credentials:</b> {@code clientId} and {@code secret} are read from environment
 * variables and injected by Spring into {@code @Value} fields. They are added to the
 * Plaid request body just before each call — not stored in the incoming
 * {@link PlaidEnrichRequest} — so credentials never travel through the business logic layer.
 */
@Service
@Slf4j
public class PlaidApiClient {

    /**
     * Pre-compiled regex that matches Plaid's {@code "error_code": "VALUE"} JSON field.
     *
     * <p>Using a compiled {@code Pattern} constant instead of inline string manipulation
     * avoids the brittle magic-number index arithmetic that was here before, and is also
     * more efficient because the pattern is only compiled once at class-load time rather
     * than on every error response.
     *
     * <p>The pattern captures one group: the value between the quotes after
     * {@code "error_code":}, ignoring optional whitespace after the colon.
     */
    private static final Pattern ERROR_CODE_PATTERN =
            Pattern.compile("\"error_code\":\\s*\"([^\"]+)\"");

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;

    /** Plaid enrichment endpoint path, e.g. {@code /enrich/transactions}. */
    @Value("${plaid.api.enrich-endpoint}")
    private String enrichEndpoint;

    /** Plaid API key read from the {@code PLAID_API_KEY} environment variable. */
    @Value("${plaid.api.api-key}")
    private String apiKey;

    /** Plaid client ID read from the {@code PLAID_CLIENT_ID} environment variable. */
    @Value("${plaid.api.client-id}")
    private String clientId;

    /**
     * Spring injects all dependencies via this constructor.
     * The three Resilience4j objects are configured in
     * {@link com.td.enrich.config.ResilienceConfig} and their settings come from
     * {@code application.yml}.
     *
     * @param plaidWebClient     the configured WebClient from {@link com.td.enrich.config.WebClientConfig}
     * @param plaidCircuitBreaker the circuit breaker that guards Plaid calls
     * @param plaidRetry          the retry policy for transient Plaid failures
     * @param plaidBulkhead       the concurrency limiter for Plaid calls
     */
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
     * Sends a single enrichment request to Plaid and returns the response reactively.
     *
     * <p>The method returns a {@code Mono<PlaidEnrichResponse>} — a "promise" of a
     * future value. No HTTP call is made until something subscribes to it (either by
     * calling {@code .block()} or by connecting it to another reactive stream).
     *
     * <p><b>Error handling:</b>
     * <ul>
     *   <li>4xx responses → {@link PlaidApiException} (not retried)</li>
     *   <li>5xx responses → {@link PlaidApiException} (retried up to 3 times)</li>
     *   <li>Network errors / timeouts → wrapped in {@link PlaidApiException} (retried)</li>
     *   <li>Any other {@code Exception} → wrapped in {@link PlaidApiException}</li>
     * </ul>
     *
     * <p>Callers only need to handle {@link PlaidApiException} — all error types
     * are normalized to that one type before leaving this method.
     *
     * @param request the enrichment request; {@code clientId} and {@code secret}
     *                are populated inside this method before the call is made
     * @return a {@code Mono} that emits the Plaid response, or errors with
     *         {@link PlaidApiException} if the call fails after retries
     */
    public Mono<PlaidEnrichResponse> enrichTransactions(PlaidEnrichRequest request) {
        log.info("Calling Plaid Enrich API for account: {}", request.accountId());

        // Inject credentials here, not in the caller, so credentials stay out of service/domain code
        PlaidEnrichRequest requestWithCredentials = new PlaidEnrichRequest(
                clientId,
                apiKey,
                request.accountId(),
                request.transactions()
        );

        return webClient
                .post()
                .uri(enrichEndpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestWithCredentials)
                .retrieve()
                // 4xx client errors: don't retry — a bad request won't fix itself
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("Plaid API 4xx error: status={}, body={}",
                                    response.statusCode(), body);
                            return Mono.error(new PlaidApiException(
                                    "Client error from Plaid API: " + body,
                                    response.statusCode().value()
                            ));
                        }))
                // 5xx server errors: throw PlaidApiException so the retry operator picks them up
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class).flatMap(body -> {
                            log.error("Plaid API 5xx error: status={}, body={}",
                                    response.statusCode(), body);
                            return Mono.error(new PlaidApiException(
                                    "Server error from Plaid API: " + body,
                                    response.statusCode().value()
                            ));
                        }))
                .bodyToMono(PlaidEnrichResponse.class)
                .doOnSuccess(response ->
                        log.info("Plaid enrichment successful: {} transaction(s) returned",
                                response.enrichedTransactions().size()))
                .doOnError(error ->
                        log.error("Plaid API call failed: {}", error.getMessage()))
                // transformDeferred means the operator is re-applied per subscription,
                // which is required for stateful operators like circuit breaker and bulkhead
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                // Normalize any remaining WebClient exception to PlaidApiException
                .onErrorMap(WebClientResponseException.class, this::mapToPlaidApiException)
                .onErrorMap(Exception.class, ex -> {
                    // If it's already a PlaidApiException, pass it through unchanged;
                    // otherwise wrap it so callers always see one consistent error type
                    if (ex instanceof PlaidApiException) {
                        return ex;
                    }
                    return new PlaidApiException("Unexpected error calling Plaid API", ex);
                });
    }

    /**
     * Enriches multiple requests in parallel by calling
     * {@link #enrichTransactions(PlaidEnrichRequest)} for each one concurrently.
     *
     * <p>{@code Flux.fromIterable} turns the list into a stream of items.
     * {@code flatMap} starts a concurrent subscription for each item. By default,
     * Reactor's {@code flatMap} allows up to 256 concurrent inner subscriptions —
     * the bulkhead limits the actual Plaid concurrency lower than that.
     *
     * @param requests list of requests to enrich in parallel
     * @return a {@code Flux} that emits one {@link PlaidEnrichResponse} per request
     *         in completion order (not necessarily input order)
     */
    public Flux<PlaidEnrichResponse> enrichTransactionsBatch(List<PlaidEnrichRequest> requests) {
        log.info("Starting parallel batch enrichment for {} request(s)", requests.size());

        return Flux.fromIterable(requests)
                .flatMap(this::enrichTransactions)
                .doOnComplete(() ->
                        log.info("Parallel batch enrichment completed for {} request(s)", requests.size()));
    }

    /**
     * Lightweight connectivity check. Calls Plaid's root URL and returns
     * {@code true} if it responds with any 2xx status.
     *
     * <p>Used by the Spring Boot Actuator health endpoint to report whether
     * Plaid is reachable. Returns {@code false} on any error (timeout, 5xx, etc.)
     * rather than propagating an exception, because a health check failure should
     * degrade gracefully.
     *
     * @return a {@code Mono<Boolean>} — {@code true} if Plaid is reachable
     */
    public Mono<Boolean> healthCheck() {
        return webClient
                .get()
                .uri("/health")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false); // any error → report unhealthy, don't throw
    }

    /**
     * Converts a {@link WebClientResponseException} (Spring's HTTP error type) into
     * a {@link PlaidApiException} that carries both the HTTP status code and, if
     * present in the response body, Plaid's own error code string.
     *
     * @param ex the WebClient HTTP error exception
     * @return a new {@link PlaidApiException} with all available error details
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
     * Attempts to extract Plaid's {@code error_code} field from the response body.
     *
     * <p>Plaid error responses look like:
     * <pre>{@code { "error_type": "API_ERROR", "error_code": "INTERNAL_SERVER_ERROR", ... }}</pre>
     *
     * <p>Uses {@link #ERROR_CODE_PATTERN} rather than manual index arithmetic so the
     * extraction is self-documenting, handles optional whitespace after the colon, and
     * reduces cyclomatic complexity from 4 to 2 (one try/catch, one conditional return).
     *
     * @param ex the WebClient exception whose body may contain a Plaid error code
     * @return the extracted error code string, or {@code null} if none was found
     */
    private String extractPlaidErrorCode(WebClientResponseException ex) {
        try {
            Matcher matcher = ERROR_CODE_PATTERN.matcher(ex.getResponseBodyAsString());
            return matcher.find() ? matcher.group(1) : null;
        } catch (Exception e) {
            log.debug("Could not extract Plaid error code from response body", e);
            return null;
        }
    }
}
