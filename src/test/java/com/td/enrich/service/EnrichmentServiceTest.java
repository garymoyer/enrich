package com.td.enrich.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.td.enrich.domain.*;
import com.td.enrich.exception.PlaidApiException;
import com.td.enrich.util.GuidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EnrichmentService}.
 *
 * <p><b>What these tests cover:</b>
 * <ul>
 *   <li>The cache-check → Plaid-call → cache-write → response assembly flow.</li>
 *   <li>Cache-hit path (Plaid is never called when data is already cached).</li>
 *   <li>Cache-miss path (Plaid is called and the result is stored in the cache).</li>
 *   <li>Partial cache hits (some transactions cached, others not).</li>
 *   <li>Error handling (Plaid failures produce a FAILED response, never a thrown exception).</li>
 *   <li>Field mapping (request fields map correctly to the Plaid API format and back).</li>
 *   <li>Metadata merging (Plaid's optional {@code enrichmentMetadata} is included in the response).</li>
 * </ul>
 *
 * <p><b>Testing approach:</b> All collaborators ({@link PlaidApiClient},
 * {@link EnrichmentRepository}, {@link MerchantCacheRepository}, {@link GuidGenerator})
 * are replaced with Mockito mocks. This lets each test set up exactly the scenario it
 * cares about — a cache hit, a Plaid failure, a concurrent insert race — without
 * needing a running database or HTTP server.
 *
 * <p>{@code @ExtendWith(MockitoExtension.class)} tells JUnit 5 to create the mocks
 * annotated with {@code @Mock} and inject them before each test method runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrichmentService Unit Tests")
class EnrichmentServiceTest {

    @Mock
    private PlaidApiClient plaidApiClient;

    @Mock
    private EnrichmentRepository enrichmentRepository;

    @Mock
    private MerchantCacheRepository merchantCacheRepository;

    @Mock
    private GuidGenerator guidGenerator;

    private EnrichmentService enrichmentService;

    /**
     * A real {@link ObjectMapper} is used rather than a mock because the service
     * uses it for JSON serialization/deserialization; mocking it would require
     * stubbing many method calls and wouldn't exercise the real serialization logic.
     */
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // findAndRegisterModules registers the JavaTimeModule needed to serialize
        // LocalDate and OffsetDateTime fields that appear in the domain objects
        objectMapper.findAndRegisterModules();

        enrichmentService = new EnrichmentService(
                plaidApiClient,
                enrichmentRepository,
                merchantCacheRepository,
                guidGenerator,
                objectMapper
        );
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should successfully enrich transactions")
    void shouldSuccessfullyEnrichTransactions() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        // guidGenerator.generate() is called twice: once for the request ID, once for the merchantId
        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty()); // cache miss — forces a Plaid call
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(1);
        assertThat(response.enrichedTransactions().get(0).merchantName())
                .isEqualTo("Starbucks Coffee");
        assertThat(response.enrichedTransactions().get(0).merchantId()).isNotNull();

        // Verify collaborator interactions: Plaid called once, two saves (PENDING + SUCCESS)
        verify(guidGenerator, times(2)).generate();
        verify(plaidApiClient).enrichTransactions(any());
        verify(enrichmentRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("Should handle Plaid API failure gracefully")
    void shouldHandlePlaidApiFailure() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidApiException exception = new PlaidApiException("API Error", 500);

        when(guidGenerator.generate()).thenReturn(requestId);
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        // Mono.error signals the reactive stream failed — equivalent to Plaid returning 500
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.error(exception));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — service must NOT throw; it must return a structured FAILED response
        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("API Error");
        assertThat(response.enrichedTransactions()).isEmpty();

        // Verify the FAILED status was persisted
        ArgumentCaptor<EnrichmentEntity> entityCaptor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(entityCaptor.capture());

        EnrichmentEntity failedEntity = entityCaptor.getAllValues().get(1);
        assertThat(failedEntity.getStatus()).isEqualTo("FAILED");
        assertThat(failedEntity.getErrorMessage()).isNotNull();
    }

    @Test
    @DisplayName("Should persist request before calling Plaid API")
    void shouldPersistRequestBeforeCallingPlaidApi() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then — the first save must record PENDING status (audit trail)
        ArgumentCaptor<EnrichmentEntity> entityCaptor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(entityCaptor.capture());

        EnrichmentEntity initialEntity = entityCaptor.getAllValues().get(0);
        assertThat(initialEntity.getRequestId()).isEqualTo(requestId);
        assertThat(initialEntity.getStatus()).isEqualTo("PENDING");
        assertThat(initialEntity.getOriginalRequest()).isNotNull();
    }

    // ── getEnrichmentById ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Should retrieve enrichment by ID successfully")
    void shouldRetrieveEnrichmentById() throws Exception {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest originalReq = createTestRequest();
        String originalRequestJson = objectMapper.writeValueAsString(originalReq);

        MerchantCacheEntity cacheEntry = buildCacheEntity("merchant-id-001");

        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest(originalRequestJson)
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(cacheEntry));

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then
        assertThat(response).isPresent();
        assertThat(response.get().requestId()).isEqualTo(requestId);
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).hasSize(1);
        assertThat(response.get().enrichedTransactions().get(0).merchantId()).isEqualTo("merchant-id-001");
    }

    @Test
    @DisplayName("Should return empty when enrichment ID not found")
    void shouldReturnEmptyWhenIdNotFound() {
        // Given
        String requestId = "non-existent-id";
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.empty());

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then — Optional.empty() tells the controller to return 404
        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("Should return FAILED status when retrieving a failed enrichment by ID")
    void shouldRetrieveFailedEnrichmentById() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest("{}")
                .status("FAILED")
                .errorMessage("API connection refused")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then — FAILED records return the stored error message and an empty transaction list
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("FAILED");
        assertThat(response.get().errorMessage()).isEqualTo("API connection refused");
        assertThat(response.get().enrichedTransactions()).isEmpty();
    }

    @Test
    @DisplayName("Should return empty enriched list when stored request has null transactions")
    void shouldReturnEmptyListWhenStoredRequestHasNullTransactions() throws Exception {
        // Given — a SUCCESS entity whose originalRequest JSON deserialises to a request with no transactions
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest("{}") // "{}" deserializes to EnrichmentRequest(null, null)
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then — null transactions are handled gracefully; the cache is never queried
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).isEmpty();
        verify(merchantCacheRepository, never()).findByDescriptionAndMerchantName(any(), any());
    }

    // ── Field mapping and serialization ───────────────────────────────────────

    @Test
    @DisplayName("Should map request to Plaid format correctly")
    void shouldMapRequestToPlaidFormat() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then — the accountId and transaction list must be mapped faithfully
        ArgumentCaptor<PlaidEnrichRequest> requestCaptor = ArgumentCaptor.forClass(PlaidEnrichRequest.class);
        verify(plaidApiClient).enrichTransactions(requestCaptor.capture());

        PlaidEnrichRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.accountId()).isEqualTo(request.accountId());
        assertThat(capturedRequest.transactions()).hasSize(request.transactions().size());
    }

    @Test
    @DisplayName("Should map all transaction fields when converting to Plaid format")
    void shouldMapAllTransactionFieldsToPlaidFormat() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then — individual transaction fields must be mapped, not just list size
        ArgumentCaptor<PlaidEnrichRequest> requestCaptor = ArgumentCaptor.forClass(PlaidEnrichRequest.class);
        verify(plaidApiClient).enrichTransactions(requestCaptor.capture());

        PlaidEnrichRequest.PlaidTransaction mapped = requestCaptor.getValue().transactions().get(0);
        assertThat(mapped).isNotNull();
        assertThat(mapped.description()).isEqualTo("STARBUCKS COFFEE");
        assertThat(mapped.amount()).isEqualByComparingTo(new BigDecimal("5.75"));
    }

    @Test
    @DisplayName("Should include metadata in enriched response")
    void shouldIncludeMetadataInEnrichedResponse() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — all three standard metadata keys must be present
        assertThat(response.enrichedTransactions()).isNotEmpty();
        EnrichmentResponse.EnrichedTransaction enriched = response.enrichedTransactions().get(0);
        assertThat(enriched.metadata()).isNotEmpty();
        assertThat(enriched.metadata()).containsKey("categoryId");
        assertThat(enriched.metadata()).containsKey("website");
        assertThat(enriched.metadata()).containsKey("confidenceLevel");
    }

    @Test
    @DisplayName("Should merge enrichmentMetadata into transaction metadata")
    void shouldMergeEnrichmentMetadataIntoResponse() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        // createTestPlaidResponse includes enrichmentMetadata = {"location": "Seattle, WA"}
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — enrichmentMetadata entries must be merged into the returned metadata map
        assertThat(response.enrichedTransactions().get(0).metadata())
                .containsKey("location")
                .containsEntry("location", "Seattle, WA");
    }

    @Test
    @DisplayName("Should persist Plaid response JSON on the entity after successful enrichment")
    void shouldPersistPlaidResponseOnEntity() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then — the second enrichmentRepository.save() must carry the serialised Plaid response
        ArgumentCaptor<EnrichmentEntity> entityCaptor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(entityCaptor.capture());

        EnrichmentEntity savedAfterResponse = entityCaptor.getAllValues().get(1);
        assertThat(savedAfterResponse.getPlaidResponse()).isNotNull();
        assertThat(savedAfterResponse.getStatus()).isEqualTo("SUCCESS");
    }

    // ── Cache behaviour ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Should serve from merchant cache on cache hit — Plaid is never called")
    void shouldServeFromCacheOnCacheHit() throws Exception {
        // Given — the merchant is already in the DB cache
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        MerchantCacheEntity cacheEntry = buildCacheEntity("merchant-id-cached");

        when(guidGenerator.generate()).thenReturn(requestId);
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(cacheEntry));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — Plaid is never called; the cached merchantId is returned
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(1);
        assertThat(response.enrichedTransactions().get(0).merchantId()).isEqualTo("merchant-id-cached");

        verify(plaidApiClient, never()).enrichTransactions(any());
        verify(merchantCacheRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should call Plaid and store cache entry on cache miss")
    void shouldCallPlaidAndStoreCacheOnCacheMiss() throws Exception {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then — the freshly created cache row must have the correct description and merchantName
        ArgumentCaptor<MerchantCacheEntity> cacheCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(cacheCaptor.capture());

        MerchantCacheEntity saved = cacheCaptor.getValue();
        assertThat(saved.getMerchantId()).isEqualTo("merchant-id-001");
        assertThat(saved.getDescription()).isEqualTo("STARBUCKS COFFEE");
        assertThat(saved.getMerchantName()).isEqualTo("Starbucks");
        assertThat(saved.getPlaidResponse()).isNotNull();
    }

    @Test
    @DisplayName("Should handle partial cache hit — only uncached transactions go to Plaid")
    void shouldHandlePartialCacheHit() throws Exception {
        // Given — two transactions: first is cached, second is not
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = new EnrichmentRequest(
                "acc_12345",
                List.of(
                        new EnrichmentRequest.Transaction(
                                "STARBUCKS COFFEE", new BigDecimal("5.75"),
                                LocalDate.of(2026, 1, 30), "Starbucks"),
                        new EnrichmentRequest.Transaction(
                                "AMAZON PRIME", new BigDecimal("14.99"),
                                LocalDate.of(2026, 1, 30), "Amazon")
                )
        );

        MerchantCacheEntity cachedEntry = buildCacheEntity("merchant-id-cached");

        PlaidEnrichResponse freshPlaidResponse = new PlaidEnrichResponse(
                List.of(new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn_002", "Shopping", "cat_shopping", "Amazon",
                        "https://logo.clearbit.com/amazon.com",
                        "https://www.amazon.com", "HIGH", null
                )),
                "plaid_req_002"
        );

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-fresh");
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(cachedEntry));  // hit
        when(merchantCacheRepository.findByDescriptionAndMerchantName("AMAZON PRIME", "Amazon"))
                .thenReturn(Optional.empty());            // miss
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(freshPlaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — two enriched transactions; Plaid only called with the 1 uncached transaction
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(2);

        List<String> merchantIds = response.enrichedTransactions().stream()
                .map(EnrichmentResponse.EnrichedTransaction::merchantId)
                .toList();
        assertThat(merchantIds).containsExactly("merchant-id-cached", "merchant-id-fresh");

        ArgumentCaptor<PlaidEnrichRequest> plaidCaptor = ArgumentCaptor.forClass(PlaidEnrichRequest.class);
        verify(plaidApiClient).enrichTransactions(plaidCaptor.capture());
        assertThat(plaidCaptor.getValue().transactions()).hasSize(1);
        assertThat(plaidCaptor.getValue().transactions().get(0).description()).isEqualTo("AMAZON PRIME");
    }

    @Test
    @DisplayName("Should return merchantId from cache in getEnrichmentById")
    void shouldReturnMerchantIdFromCacheInGetById() throws Exception {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest originalReq = createTestRequest();
        String originalRequestJson = objectMapper.writeValueAsString(originalReq);

        MerchantCacheEntity cacheEntry = buildCacheEntity("merchant-id-from-cache");

        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest(originalRequestJson)
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", "Starbucks"))
                .thenReturn(Optional.of(cacheEntry));

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then — the merchantId comes from the cache, not from a new Plaid call
        assertThat(response).isPresent();
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).hasSize(1);
        assertThat(response.get().enrichedTransactions().get(0).merchantId())
                .isEqualTo("merchant-id-from-cache");

        verify(plaidApiClient, never()).enrichTransactions(any());
    }

    @Test
    @DisplayName("Should coerce null merchantName to empty string for cache key")
    void shouldHandleNullMerchantName() {
        // Given — transaction has no merchantName (null)
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = new EnrichmentRequest(
                "acc_12345",
                List.of(new EnrichmentRequest.Transaction(
                        "STARBUCKS COFFEE",
                        new BigDecimal("5.75"),
                        LocalDate.of(2026, 1, 30),
                        null  // null — triggers the nvl() null-coercion path
                ))
        );
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId, "merchant-id-001");
        // Cache key uses empty string for merchantName, not null — this is the invariant being tested
        when(merchantCacheRepository.findByDescriptionAndMerchantName("STARBUCKS COFFEE", ""))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then — should succeed, and the cache row must store empty string (not null)
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.enrichedTransactions()).hasSize(1);

        ArgumentCaptor<MerchantCacheEntity> cacheCaptor =
                ArgumentCaptor.forClass(MerchantCacheEntity.class);
        verify(merchantCacheRepository).save(cacheCaptor.capture());
        assertThat(cacheCaptor.getValue().getMerchantName()).isEqualTo("");
    }

    // ── Batch enrichment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Should enrich transactions in batch")
    void shouldEnrichTransactionsBatch() {
        // Given — two identical requests; each gets its own UUID and is processed independently
        String requestId1 = "id-batch-001";
        String requestId2 = "id-batch-002";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId1, requestId2, "merchant-id-001", "merchant-id-002");
        when(merchantCacheRepository.findByDescriptionAndMerchantName(any(), any()))
                .thenReturn(Optional.empty());
        when(merchantCacheRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        List<EnrichmentResponse> responses = enrichmentService.enrichTransactionsBatch(
                List.of(request, request));

        // Then — both responses must succeed
        assertThat(responses).hasSize(2);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("SUCCESS");
            assertThat(r.enrichedTransactions()).hasSize(1);
        });
    }

    // ── Test data helpers ──────────────────────────────────────────────────────

    /**
     * Creates a standard single-transaction request for use in multiple tests.
     * Starbucks Coffee is used as the merchant because it appears in several
     * helper methods and mock setups in this class.
     */
    private EnrichmentRequest createTestRequest() {
        return new EnrichmentRequest(
                "acc_12345",
                List.of(new EnrichmentRequest.Transaction(
                        "STARBUCKS COFFEE",
                        new BigDecimal("5.75"),
                        LocalDate.of(2026, 1, 30),
                        "Starbucks"
                ))
        );
    }

    /**
     * Creates a Plaid response that matches the transaction in {@link #createTestRequest()}.
     * Includes an {@code enrichmentMetadata} map so tests can verify that field is merged.
     */
    private PlaidEnrichResponse createTestPlaidResponse() {
        return new PlaidEnrichResponse(
                List.of(new PlaidEnrichResponse.PlaidEnrichedTransaction(
                        "txn_001",
                        "Food & Drink",
                        "13005000",
                        "Starbucks Coffee",
                        "https://logo.clearbit.com/starbucks.com",
                        "https://www.starbucks.com",
                        "HIGH",
                        Map.of("location", "Seattle, WA")
                )),
                "plaid_550e8400-e29b-41d4-a716-446655440000"
        );
    }

    /**
     * Builds a pre-populated {@link MerchantCacheEntity} whose {@code plaidResponse}
     * JSON is consistent with the data in {@link #createTestPlaidResponse()}.
     *
     * <p>Used in tests that exercise the cache-hit path: we need the entity to already
     * contain valid JSON so the service can deserialize it back into an
     * {@code EnrichedTransaction}.
     *
     * @param merchantId the UUID to assign to this cache entry
     */
    private MerchantCacheEntity buildCacheEntity(String merchantId) throws JsonProcessingException {
        PlaidEnrichResponse.PlaidEnrichedTransaction plaidTx =
                createTestPlaidResponse().enrichedTransactions().get(0);
        String plaidTxJson = objectMapper.writeValueAsString(plaidTx);

        MerchantCacheEntity entity = new MerchantCacheEntity();
        entity.setMerchantId(merchantId);
        entity.setDescription("STARBUCKS COFFEE");
        entity.setMerchantName("Starbucks");
        entity.setPlaidResponse(plaidTxJson);
        entity.setCreatedAt(OffsetDateTime.now());
        return entity;
    }
}
