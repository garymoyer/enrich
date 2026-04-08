package com.td.enrich.controller;

import com.td.enrich.domain.EnrichmentRequest;
import com.td.enrich.domain.EnrichmentResponse;
import com.td.enrich.service.EnrichmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller exposing the transaction enrichment API.
 *
 * <p>All endpoints are mounted under {@code /api/v1/enrich}. This class is responsible
 * only for HTTP concerns: reading the request, calling the service, and writing the
 * response. All business logic lives in {@link EnrichmentService}.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>{@code POST /api/v1/enrich} — synchronous single enrichment</li>
 *   <li>{@code POST /api/v1/enrich/batch} — synchronous parallel batch enrichment</li>
 *   <li>{@code GET  /api/v1/enrich/{requestId}} — retrieve a stored result by UUID</li>
 *   <li>{@code GET  /api/v1/enrich/health} — liveness check</li>
 * </ul>
 *
 * <p>Input validation is handled declaratively: {@code @Valid} on the request body
 * parameter triggers Spring's Bean Validation before this method body runs. If
 * validation fails, {@link com.td.enrich.exception.GlobalExceptionHandler} returns
 * a 400 response automatically — no manual validation code needed here.
 *
 * <p>{@code @Tag} and {@code @Operation} annotations are read by springdoc to generate
 * the Swagger UI documentation at {@code /swagger-ui.html}.
 */
@RestController
@RequestMapping("/api/v1/enrich")
@Tag(name = "Enrichment", description = "Transaction enrichment endpoints")
@Slf4j
public class EnrichmentController {

    private final EnrichmentService enrichmentService;

    /**
     * Spring injects {@link EnrichmentService} via constructor injection — the
     * recommended approach because it makes the dependency explicit and testable.
     *
     * @param enrichmentService the service that contains all enrichment business logic
     */
    public EnrichmentController(EnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    /**
     * Enriches one or more transactions synchronously (blocking).
     *
     * <p>The caller sends a JSON body with an {@code accountId} and a list of
     * transactions. This method blocks until Plaid returns data (or the merchant
     * cache is hit) and returns the enriched result in the same HTTP response.
     *
     * <p>Cache hits are typically &lt; 1 ms; cache misses that call Plaid are typically
     * 100–500 ms.
     *
     * @param request validated request body; Spring rejects invalid bodies with 400
     *                before this method is called
     * @return 200 OK with an {@link EnrichmentResponse}; status field is
     *         {@code SUCCESS} or {@code FAILED}
     */
    @PostMapping
    @Operation(
            summary = "Enrich transactions",
            description = "Enriches transaction data using the Plaid API. Returns enriched merchant " +
                          "information (name, category, logo) for each transaction. Cache hits return " +
                          "in under 1 ms; cache misses call Plaid and typically return within 500 ms.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Enrichment completed — check the 'status' field for SUCCESS or FAILED",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Request failed validation — response body lists the invalid fields",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "502",
                    description = "Plaid API returned an error — check 'plaidStatusCode' in the response",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    })
    public ResponseEntity<EnrichmentResponse> enrichTransactions(
            @Parameter(description = "Enrichment request with accountId and transactions list",
                       required = true)
            @Valid @RequestBody EnrichmentRequest request) {

        log.info("Enrichment request received for account: {}, {} transaction(s)",
                request.accountId(), request.transactions().size());

        EnrichmentResponse response = enrichmentService.enrichTransactions(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Enriches multiple batches of transactions in parallel (synchronous — blocks until all complete).
     *
     * <p>Each element in the input list is an independent {@link EnrichmentRequest} with its
     * own {@code accountId} and transactions. All batches are processed concurrently using
     * Project Reactor's {@code Flux.flatMap}. The response list is in the same order as the
     * input list.
     *
     * <p>Use this endpoint when you have multiple independent account batches to enrich at
     * the same time and want to minimize total wall-clock time.
     *
     * @param requests list of enrichment requests; must be non-empty
     * @return 200 OK with a list of {@link EnrichmentResponse} objects, one per input request;
     *         400 if the list is empty
     */
    @PostMapping("/batch")
    @Operation(
            summary = "Batch enrich transactions",
            description = "Accepts a list of enrichment requests and processes them in parallel. " +
                          "Each request gets its own UUID and is processed independently. " +
                          "Returns when all requests complete.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "All batch items processed — each item has its own status field",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Request list was empty or contained invalid items"),
    })
    public ResponseEntity<List<EnrichmentResponse>> enrichTransactionsBatch(
            @Parameter(description = "List of enrichment requests to process in parallel",
                       required = true)
            @Valid @RequestBody List<EnrichmentRequest> requests) {

        log.info("Batch enrichment request received: {} batch(es)", requests.size());

        if (requests.isEmpty()) {
            // Return 400 immediately — no work to do and the response would be an empty list
            return ResponseEntity.badRequest().build();
        }

        List<EnrichmentResponse> responses = enrichmentService.enrichTransactionsBatch(requests);
        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves a previously stored enrichment result by its UUID.
     *
     * <p>Use this to look up results from earlier calls, or to poll for batch results
     * when using an async enrichment flow. The UUID is the {@code requestId} field
     * returned by any enrichment endpoint.
     *
     * @param requestId the UUID of the enrichment request to look up
     * @return 200 OK with the stored {@link EnrichmentResponse};
     *         404 Not Found if no record exists for that UUID
     */
    @GetMapping("/{requestId}")
    @Operation(
            summary = "Get enrichment result by ID",
            description = "Retrieves a stored enrichment result using the UUID returned by the " +
                          "enrich or batch endpoints. Returns 404 if the UUID is not found.")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Enrichment record found",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "No enrichment record found for the given UUID")
    })
    public ResponseEntity<EnrichmentResponse> getEnrichmentById(
            @Parameter(description = "Request UUID from a previous enrichment call",
                       example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String requestId) {

        log.debug("Fetching enrichment record: {}", requestId);

        // map(ResponseEntity::ok) wraps the found value in 200 OK;
        // orElse(notFound) returns 404 if the Optional is empty
        return enrichmentService.getEnrichmentById(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Simple health check endpoint for load balancers and monitoring tools.
     *
     * <p>Returns HTTP 200 with a plain text message when the service is running.
     * For deeper health checks (database connectivity, circuit breaker state), use the
     * Spring Boot Actuator endpoint at {@code /actuator/health}.
     *
     * @return 200 OK with a static confirmation string
     */
    @GetMapping("/health")
    @Operation(
            summary = "Service health check",
            description = "Returns 200 OK when the service is running. For full dependency " +
                          "health (DB, Plaid API), use /actuator/health instead.")
    @ApiResponse(responseCode = "200", description = "Service is running")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Enrichment service is healthy");
    }
}
