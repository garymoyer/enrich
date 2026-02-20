package com.plaid.enrich.controller;

import com.plaid.enrich.domain.EnrichmentRequest;
import com.plaid.enrich.domain.EnrichmentResponse;
import com.plaid.enrich.service.EnrichmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for transaction enrichment endpoints.
 * Provides APIs for single and batch enrichment, and retrieval by GUID.
 */
@RestController
@RequestMapping("/api/v1/enrich")
@Tag(name = "Enrichment", description = "Transaction enrichment APIs")
@Slf4j
public class EnrichmentController {

    private final EnrichmentService enrichmentService;

    public EnrichmentController(EnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    /**
     * Enriches a single transaction request.
     */
    @PostMapping
    @Operation(
            summary = "Enrich transactions",
            description = "Enriches transaction data using Plaid API. " +
                         "Generates a unique GUID for tracking and persists the request/response pair."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully enriched transactions",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request payload",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "Plaid API error",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))
            )
    })
    public ResponseEntity<EnrichmentResponse> enrichTransactions(
            @Parameter(description = "Enrichment request containing transactions to enrich", required = true)
            @Valid @RequestBody EnrichmentRequest request) {

        log.info("Received enrichment request for account: {}", request.accountId());

        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        return ResponseEntity.ok(response);
    }

    /**
     * Enriches multiple transaction requests in parallel.
     */
    @PostMapping("/batch")
    @Operation(
            summary = "Batch enrich transactions",
            description = "Enriches multiple transaction batches in parallel. " +
                         "Each batch receives its own GUID and is processed independently."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully processed batch enrichment",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request payload",
                    content = @Content(schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))
            )
    })
    public ResponseEntity<List<EnrichmentResponse>> enrichTransactionsBatch(
            @Parameter(description = "List of enrichment requests", required = true)
            @Valid @RequestBody List<EnrichmentRequest> requests) {

        log.info("Received batch enrichment request with {} items", requests.size());

        if (requests.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<EnrichmentResponse> responses = enrichmentService.enrichTransactionsBatch(requests);

        return ResponseEntity.ok(responses);
    }

    /**
     * Retrieves an enrichment record by its GUID.
     */
    @GetMapping("/{requestId}")
    @Operation(
            summary = "Get enrichment by ID",
            description = "Retrieves a previously processed enrichment request by its GUID"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Enrichment record found",
                    content = @Content(schema = @Schema(implementation = EnrichmentResponse.class))
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Enrichment record not found"
            )
    })
    public ResponseEntity<EnrichmentResponse> getEnrichmentById(
            @Parameter(description = "Request GUID", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable String requestId) {

        log.debug("Retrieving enrichment record: {}", requestId);

        return enrichmentService.getEnrichmentById(requestId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Health check endpoint for the controller.
     */
    @GetMapping("/health")
    @Operation(
            summary = "Health check",
            description = "Simple health check endpoint for the enrichment service"
    )
    @ApiResponse(
            responseCode = "200",
            description = "Service is healthy"
    )
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Enrichment service is healthy");
    }
}
