package com.plaid.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.*;
import com.plaid.enrich.exception.PlaidApiException;
import com.plaid.enrich.util.GuidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for orchestrating transaction enrichment.
 * Responsibilities:
 * - Generate GUID for each request
 * - Check local merchant cache before calling Plaid API
 * - Call Plaid API via PlaidApiClient only for cache misses
 * - Persist merchant cache entries keyed on (description, merchantName)
 * - Persist request/response pairs
 * - Map between domain models
 * - Handle both single and batch requests
 */
@Service
@Slf4j
public class EnrichmentService {

    private final PlaidApiClient plaidApiClient;
    private final EnrichmentRepository enrichmentRepository;
    private final MerchantCacheRepository merchantCacheRepository;
    private final GuidGenerator guidGenerator;
    private final ObjectMapper objectMapper;

    public EnrichmentService(PlaidApiClient plaidApiClient,
                             EnrichmentRepository enrichmentRepository,
                             MerchantCacheRepository merchantCacheRepository,
                             GuidGenerator guidGenerator,
                             ObjectMapper objectMapper) {
        this.plaidApiClient = plaidApiClient;
        this.enrichmentRepository = enrichmentRepository;
        this.merchantCacheRepository = merchantCacheRepository;
        this.guidGenerator = guidGenerator;
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches transactions for a single request.
     * Cache hits skip Plaid entirely; misses call Plaid, populate the cache, then return.
     *
     * @param request the enrichment request from client
     * @return enrichment response with GUID and enriched data
     */
    @Transactional
    public EnrichmentResponse enrichTransactions(EnrichmentRequest request) {
        String requestId = guidGenerator.generate();
        log.info("Starting enrichment for request: {}", requestId);

        try {
            persistRequest(requestId, request, "PENDING");
            EnrichmentResponse response = enrichCore(requestId, request);
            log.info("Successfully enriched request: {}", requestId);
            return response;

        } catch (Exception ex) {
            log.error("Error enriching request: {}", requestId, ex);
            persistResponse(requestId, request, null, "FAILED", ex.getMessage());
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

        List<BatchItem> batchItems = requests.stream()
                .map(req -> {
                    String requestId = guidGenerator.generate();
                    persistRequest(requestId, req, "PENDING");
                    return new BatchItem(requestId, req);
                })
                .collect(Collectors.toList());

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
     * For SUCCESS records, reconstructs enriched transactions from the merchant cache
     * using the original request's (description, merchantName) pairs.
     *
     * @param requestId the GUID to look up
     * @return optional enrichment response
     */
    @Transactional(readOnly = true)
    public Optional<EnrichmentResponse> getEnrichmentById(String requestId) {
        log.debug("Retrieving enrichment record: {}", requestId);

        return enrichmentRepository.findById(requestId)
                .map(entity -> {
                    if ("SUCCESS".equals(entity.getStatus())) {
                        try {
                            EnrichmentRequest originalRequest = objectMapper.readValue(
                                    entity.getOriginalRequest(),
                                    EnrichmentRequest.class
                            );
                            List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions =
                                    buildEnrichedTransactionsFromCache(originalRequest);
                            return new EnrichmentResponse(
                                    requestId,
                                    enrichedTransactions,
                                    entity.getCreatedAt(),
                                    "SUCCESS"
                            );
                        } catch (JsonProcessingException e) {
                            log.error("Error parsing stored request for {}", requestId, e);
                            throw new RuntimeException("Error retrieving enrichment data", e);
                        }
                    } else {
                        return new EnrichmentResponse(
                                requestId,
                                List.of(),
                                entity.getCreatedAt(),
                                entity.getStatus(),
                                entity.getErrorMessage()
                        );
                    }
                });
    }

    /**
     * Core enrichment logic shared by single and batch flows.
     * Checks the merchant cache for each transaction; misses call Plaid and populate the cache.
     * Calls persistResponse(SUCCESS) at the end.
     */
    private EnrichmentResponse enrichCore(String requestId, EnrichmentRequest request) {
        Map<String, MerchantCacheEntity> cacheHits = new HashMap<>();
        List<EnrichmentRequest.Transaction> uncachedTransactions = new ArrayList<>();

        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            String mn = nvl(tx.merchantName());
            Optional<MerchantCacheEntity> cached =
                    merchantCacheRepository.findByDescriptionAndMerchantName(tx.description(), mn);
            if (cached.isPresent()) {
                cacheHits.put(cacheKey(tx.description(), mn), cached.get());
            } else {
                uncachedTransactions.add(tx);
            }
        }

        PlaidEnrichResponse plaidResponse = null;
        Map<String, MerchantCacheEntity> freshEntries = new HashMap<>();

        if (!uncachedTransactions.isEmpty()) {
            PlaidEnrichRequest plaidRequest = mapToPlaidRequest(request.accountId(), uncachedTransactions);
            plaidResponse = plaidApiClient.enrichTransactions(plaidRequest).block();

            if (plaidResponse == null) {
                throw new PlaidApiException("Plaid API returned null response");
            }

            for (int i = 0; i < uncachedTransactions.size(); i++) {
                EnrichmentRequest.Transaction tx = uncachedTransactions.get(i);
                PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx =
                        plaidResponse.enrichedTransactions().get(i);
                String mn = nvl(tx.merchantName());
                String merchantId = guidGenerator.generate();

                try {
                    String plaidTxJson = objectMapper.writeValueAsString(plaidTx);
                    MerchantCacheEntity cacheEntry = MerchantCacheEntity.builder()
                            .merchantId(merchantId)
                            .description(tx.description())
                            .merchantName(mn)
                            .plaidResponse(plaidTxJson)
                            .createdAt(OffsetDateTime.now())
                            .build();
                    merchantCacheRepository.save(cacheEntry);
                    freshEntries.put(cacheKey(tx.description(), mn), cacheEntry);
                } catch (DataIntegrityViolationException e) {
                    // Concurrent insert: another thread won the race — re-query to get the winner's entry
                    log.debug("Cache insert race detected for ({}, {}); re-querying", tx.description(), mn);
                    MerchantCacheEntity winner = merchantCacheRepository
                            .findByDescriptionAndMerchantName(tx.description(), mn)
                            .orElseThrow(() -> new RuntimeException(
                                    "Cache entry disappeared after concurrent insert race"));
                    freshEntries.put(cacheKey(tx.description(), mn), winner);
                } catch (JsonProcessingException e) {
                    log.error("Error serializing Plaid transaction for cache", e);
                    throw new RuntimeException("Error persisting merchant cache entry", e);
                }
            }
        }

        persistResponse(requestId, request, plaidResponse, "SUCCESS", null);

        // Assemble response preserving original transaction order
        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = new ArrayList<>();
        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            String mn = nvl(tx.merchantName());
            String key = cacheKey(tx.description(), mn);
            MerchantCacheEntity cacheEntry = cacheHits.containsKey(key)
                    ? cacheHits.get(key)
                    : freshEntries.get(key);

            if (cacheEntry != null) {
                try {
                    PlaidEnrichResponse.PlaidEnrichedTransaction enriched = objectMapper.readValue(
                            cacheEntry.getPlaidResponse(),
                            PlaidEnrichResponse.PlaidEnrichedTransaction.class
                    );
                    enrichedTransactions.add(new EnrichmentResponse.EnrichedTransaction(
                            enriched.id(),
                            cacheEntry.getMerchantId(),
                            enriched.category(),
                            enriched.merchantName(),
                            enriched.logoUrl(),
                            createMetadata(enriched)
                    ));
                } catch (JsonProcessingException e) {
                    log.error("Error deserializing cached Plaid transaction", e);
                }
            }
        }

        return new EnrichmentResponse(requestId, enrichedTransactions, OffsetDateTime.now(), "SUCCESS");
    }

    /**
     * Builds the enriched transaction list from the merchant cache for a given request.
     * Transactions not found in the cache are silently omitted.
     */
    private List<EnrichmentResponse.EnrichedTransaction> buildEnrichedTransactionsFromCache(
            EnrichmentRequest request) {
        if (request.transactions() == null) {
            return List.of();
        }
        List<EnrichmentResponse.EnrichedTransaction> enrichedTransactions = new ArrayList<>();
        for (EnrichmentRequest.Transaction tx : request.transactions()) {
            merchantCacheRepository
                    .findByDescriptionAndMerchantName(tx.description(), nvl(tx.merchantName()))
                    .ifPresent(cacheEntry -> {
                        try {
                            PlaidEnrichResponse.PlaidEnrichedTransaction enriched = objectMapper.readValue(
                                    cacheEntry.getPlaidResponse(),
                                    PlaidEnrichResponse.PlaidEnrichedTransaction.class
                            );
                            enrichedTransactions.add(new EnrichmentResponse.EnrichedTransaction(
                                    enriched.id(),
                                    cacheEntry.getMerchantId(),
                                    enriched.category(),
                                    enriched.merchantName(),
                                    enriched.logoUrl(),
                                    createMetadata(enriched)
                            ));
                        } catch (JsonProcessingException e) {
                            log.error("Error deserializing cached Plaid transaction in getById", e);
                        }
                    });
        }
        return enrichedTransactions;
    }

    /**
     * Processes a single batch item reactively, delegating to enrichCore.
     */
    private Mono<EnrichmentResponse> processBatchItem(BatchItem item) {
        return Mono.fromCallable(() -> enrichCore(item.requestId(), item.originalRequest()))
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
     * Maps account ID and a list of transactions to Plaid request format.
     */
    private PlaidEnrichRequest mapToPlaidRequest(String accountId, List<EnrichmentRequest.Transaction> txs) {
        List<PlaidEnrichRequest.PlaidTransaction> plaidTransactions = txs.stream()
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
                accountId,
                plaidTransactions
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
     * Persists the response after enrichment (either from Plaid or from cache).
     */
    private void persistResponse(String requestId,
                                 EnrichmentRequest originalRequest,
                                 PlaidEnrichResponse plaidResponse,
                                 String status,
                                 String errorMessage) {
        try {
            EnrichmentEntity entity = enrichmentRepository.findById(requestId)
                    .orElseGet(() -> {
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

    /** Coerces null to empty string for cache key consistency. */
    private String nvl(String s) {
        return s != null ? s : "";
    }

    /** Composite cache key from description and (null-safe) merchant name. */
    private String cacheKey(String desc, String merchant) {
        return desc + "|" + nvl(merchant);
    }

    /**
     * Helper record for batch processing.
     */
    private record BatchItem(
            String requestId,
            EnrichmentRequest originalRequest
    ) {}
}
