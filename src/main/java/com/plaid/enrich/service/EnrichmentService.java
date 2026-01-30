package com.plaid.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.*;
import com.plaid.enrich.exception.PlaidApiException;
import com.plaid.enrich.util.GuidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for orchestrating transaction enrichment.
 * Responsibilities:
 * - Generate GUID for each request
 * - Call Plaid API via PlaidApiClient
 * - Persist request/response pairs
 * - Map between domain models
 * - Handle both single and batch requests
 */
@Service
@Slf4j
public class EnrichmentService {

    private final PlaidApiClient plaidApiClient;
    private final EnrichmentRepository enrichmentRepository;
    private final GuidGenerator guidGenerator;
    private final ObjectMapper objectMapper;

    public EnrichmentService(PlaidApiClient plaidApiClient,
                            EnrichmentRepository enrichmentRepository,
                            GuidGenerator guidGenerator,
                            ObjectMapper objectMapper) {
        this.plaidApiClient = plaidApiClient;
        this.enrichmentRepository = enrichmentRepository;
        this.guidGenerator = guidGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches transactions for a single request.
     * Generates GUID, calls Plaid API, persists data, and returns enriched response.
     *
     * @param request the enrichment request from client
     * @return enrichment response with GUID and enriched data
     */
    @Transactional
    public EnrichmentResponse enrichTransactions(EnrichmentRequest request) {
        String requestId = guidGenerator.generate();
        log.info("Starting enrichment for request: {}", requestId);

        try {
            // Convert to Plaid request format
            PlaidEnrichRequest plaidRequest = mapToPlaidRequest(request);

            // Persist initial request
            persistRequest(requestId, request, "PENDING");

            // Call Plaid API
            PlaidEnrichResponse plaidResponse = plaidApiClient
                    .enrichTransactions(plaidRequest)
                    .block(); // Block for synchronous processing

            if (plaidResponse == null) {
                throw new PlaidApiException("Plaid API returned null response");
            }

            // Persist successful response
            persistResponse(requestId, request, plaidResponse, "SUCCESS", null);

            // Map to client response
            EnrichmentResponse response = mapToEnrichmentResponse(requestId, plaidResponse);
            log.info("Successfully enriched request: {}", requestId);

            return response;

        } catch (Exception ex) {
            log.error("Error enriching request: {}", requestId, ex);

            // Persist failure
            persistResponse(requestId, request, null, "FAILED", ex.getMessage());

            // Return error response
            return new EnrichmentResponse(
                    requestId,
                    List.of(),
                    OffsetDateTime.now(),
                    "FAILED",
                    ex.getMessage()
            );
        }
    }

    /**
     * Enriches multiple transaction batches in parallel.
     * Each batch gets its own GUID and is processed independently.
     *
     * @param requests list of enrichment requests
     * @return list of enrichment responses
     */
    @Transactional
    public List<EnrichmentResponse> enrichTransactionsBatch(List<EnrichmentRequest> requests) {
        log.info("Starting batch enrichment for {} requests", requests.size());

        // Create Plaid requests with GUIDs
        List<BatchItem> batchItems = requests.stream()
                .map(req -> {
                    String requestId = guidGenerator.generate();
                    persistRequest(requestId, req, "PENDING");
                    return new BatchItem(requestId, req, mapToPlaidRequest(req));
                })
                .collect(Collectors.toList());

        // Process all requests in parallel
        List<EnrichmentResponse> responses = Flux.fromIterable(batchItems)
                .flatMap(item -> processBatchItem(item))
                .collectList()
                .block();

        log.info("Completed batch enrichment: {}/{} successful",
                responses.stream().filter(r -> "SUCCESS".equals(r.status())).count(),
                responses.size());

        return responses;
    }

    /**
     * Retrieves an enrichment record by GUID.
     *
     * @param requestId the GUID to look up
     * @return optional enrichment response
     */
    @Transactional(readOnly = true)
    public Optional<EnrichmentResponse> getEnrichmentById(String requestId) {
        log.debug("Retrieving enrichment record: {}", requestId);

        return enrichmentRepository.findById(requestId)
                .map(entity -> {
                    try {
                        if ("SUCCESS".equals(entity.getStatus())) {
                            PlaidEnrichResponse plaidResponse = objectMapper.readValue(
                                    entity.getPlaidResponse(),
                                    PlaidEnrichResponse.class
                            );
                            return mapToEnrichmentResponse(requestId, plaidResponse);
                        } else {
                            return new EnrichmentResponse(
                                    requestId,
                                    List.of(),
                                    entity.getCreatedAt(),
                                    entity.getStatus(),
                                    entity.getErrorMessage()
                            );
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Error parsing stored response for {}", requestId, e);
                        throw new RuntimeException("Error retrieving enrichment data", e);
                    }
                });
    }

    /**
     * Processes a single batch item.
     */
    private Mono<EnrichmentResponse> processBatchItem(BatchItem item) {
        return plaidApiClient.enrichTransactions(item.plaidRequest())
                .map(plaidResponse -> {
                    persistResponse(item.requestId(), item.originalRequest(), plaidResponse, "SUCCESS", null);
                    return mapToEnrichmentResponse(item.requestId(), plaidResponse);
                })
                .onErrorResume(ex -> {
                    log.error("Batch item {} failed: {}", item.requestId(), ex.getMessage());
                    persistResponse(item.requestId(), item.originalRequest(), null, "FAILED", ex.getMessage());
                    return Mono.just(new EnrichmentResponse(
                            item.requestId(),
                            List.of(),
                            OffsetDateTime.now(),
                            "FAILED",
                            ex.getMessage()
                    ));
                });
    }

    /**
     * Maps client request to Plaid request format.
     */
    private PlaidEnrichRequest mapToPlaidRequest(EnrichmentRequest request) {
        List<PlaidEnrichRequest.PlaidTransaction> plaidTransactions = request.transactions().stream()
                .map(t -> new PlaidEnrichRequest.PlaidTransaction(
                        t.description(),
                        t.amount(),
                        t.date(),
                        t.merchantName()
                ))
                .collect(Collectors.toList());

        return new PlaidEnrichRequest(
                null, // Will be set in PlaidApiClient
                null, // Will be set in PlaidApiClient
                request.accountId(),
                plaidTransactions
        );
    }

    /**
     * Maps Plaid response to client response format.
     */
    private EnrichmentResponse mapToEnrichmentResponse(String requestId, PlaidEnrichResponse plaidResponse) {
        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = plaidResponse.enrichedTransactions().stream()
                .map(t -> new EnrichmentResponse.EnrichedTransaction(
                        t.id(),
                        t.category(),
                        t.merchantName(),
                        t.logoUrl(),
                        createMetadata(t)
                ))
                .collect(Collectors.toList());

        return new EnrichmentResponse(
                requestId,
                enrichedTransactions,
                OffsetDateTime.now(),
                "SUCCESS"
        );
    }

    /**
     * Creates metadata map from Plaid enriched transaction.
     */
    private Map<String, Object> createMetadata(PlaidEnrichResponse.PlaidEnrichedTransaction transaction) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("categoryId", transaction.categoryId());
        metadata.put("website", transaction.website());
        metadata.put("confidenceLevel", transaction.confidenceLevel());

        if (transaction.enrichmentMetadata() != null) {
            metadata.putAll(transaction.enrichmentMetadata());
        }

        return metadata;
    }

    /**
     * Persists the initial request.
     */
    private void persistRequest(String requestId, EnrichmentRequest request, String status) {
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            EnrichmentEntity entity = EnrichmentEntity.builder()
                    .requestId(requestId)
                    .originalRequest(requestJson)
                    .status(status)
                    .createdAt(OffsetDateTime.now())
                    .build();

            enrichmentRepository.save(entity);
            log.debug("Persisted request: {}", requestId);
        } catch (JsonProcessingException e) {
            log.error("Error serializing request", e);
            throw new RuntimeException("Error persisting request", e);
        }
    }

    /**
     * Persists the response after Plaid API call.
     */
    private void persistResponse(String requestId,
                                 EnrichmentRequest originalRequest,
                                 PlaidEnrichResponse plaidResponse,
                                 String status,
                                 String errorMessage) {
        try {
            EnrichmentEntity entity = enrichmentRepository.findById(requestId)
                    .orElseGet(() -> {
                        // If not found, create new (shouldn't happen normally)
                        String requestJson;
                        try {
                            requestJson = objectMapper.writeValueAsString(originalRequest);
                        } catch (JsonProcessingException e) {
                            requestJson = "{}";
                        }
                        return EnrichmentEntity.builder()
                                .requestId(requestId)
                                .originalRequest(requestJson)
                                .createdAt(OffsetDateTime.now())
                                .build();
                    });

            entity.setStatus(status);
            entity.setErrorMessage(errorMessage);

            if (plaidResponse != null) {
                String responseJson = objectMapper.writeValueAsString(plaidResponse);
                entity.setPlaidResponse(responseJson);
            }

            enrichmentRepository.save(entity);
            log.debug("Updated enrichment record: {} with status: {}", requestId, status);
        } catch (JsonProcessingException e) {
            log.error("Error serializing response", e);
        }
    }

    /**
     * Helper record for batch processing.
     */
    private record BatchItem(
            String requestId,
            EnrichmentRequest originalRequest,
            PlaidEnrichRequest plaidRequest
    ) {}
}
