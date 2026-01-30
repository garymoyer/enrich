package com.plaid.enrich.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plaid.enrich.domain.*;
import com.plaid.enrich.exception.PlaidApiException;
import com.plaid.enrich.util.GuidGenerator;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("EnrichmentService Unit Tests")
class EnrichmentServiceTest {

    @Mock
    private PlaidApiClient plaidApiClient;

    @Mock
    private EnrichmentRepository enrichmentRepository;

    @Mock
    private GuidGenerator guidGenerator;

    private EnrichmentService enrichmentService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        enrichmentService = new EnrichmentService(
                plaidApiClient,
                enrichmentRepository,
                guidGenerator,
                objectMapper
        );
    }

    @Test
    @DisplayName("Should successfully enrich transactions")
    void shouldSuccessfullyEnrichTransactions() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId);
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

        // Verify interactions
        verify(guidGenerator).generate();
        verify(plaidApiClient).enrichTransactions(any());
        verify(enrichmentRepository, times(2)).save(any()); // Once for request, once for response
    }

    @Test
    @DisplayName("Should handle Plaid API failure gracefully")
    void shouldHandlePlaidApiFailure() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidApiException exception = new PlaidApiException("API Error", 500);

        when(guidGenerator.generate()).thenReturn(requestId);
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.error(exception));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo(requestId);
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.errorMessage()).contains("API Error");
        assertThat(response.enrichedTransactions()).isEmpty();

        // Verify failure was persisted
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

        when(guidGenerator.generate()).thenReturn(requestId);
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then
        ArgumentCaptor<EnrichmentEntity> entityCaptor = ArgumentCaptor.forClass(EnrichmentEntity.class);
        verify(enrichmentRepository, times(2)).save(entityCaptor.capture());

        EnrichmentEntity initialEntity = entityCaptor.getAllValues().get(0);
        assertThat(initialEntity.getRequestId()).isEqualTo(requestId);
        assertThat(initialEntity.getStatus()).isEqualTo("PENDING");
        assertThat(initialEntity.getOriginalRequest()).isNotNull();
    }

    @Test
    @DisplayName("Should retrieve enrichment by ID successfully")
    void shouldRetrieveEnrichmentById() throws Exception {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();
        String responseJson = objectMapper.writeValueAsString(plaidResponse);

        EnrichmentEntity entity = EnrichmentEntity.builder()
                .requestId(requestId)
                .originalRequest("{}")
                .plaidResponse(responseJson)
                .status("SUCCESS")
                .createdAt(OffsetDateTime.now())
                .build();

        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.of(entity));

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then
        assertThat(response).isPresent();
        assertThat(response.get().requestId()).isEqualTo(requestId);
        assertThat(response.get().status()).isEqualTo("SUCCESS");
        assertThat(response.get().enrichedTransactions()).hasSize(1);
    }

    @Test
    @DisplayName("Should return empty when enrichment ID not found")
    void shouldReturnEmptyWhenIdNotFound() {
        // Given
        String requestId = "non-existent-id";
        when(enrichmentRepository.findById(requestId)).thenReturn(Optional.empty());

        // When
        Optional<EnrichmentResponse> response = enrichmentService.getEnrichmentById(requestId);

        // Then
        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("Should map request to Plaid format correctly")
    void shouldMapRequestToPlaidFormat() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId);
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        enrichmentService.enrichTransactions(request);

        // Then
        ArgumentCaptor<PlaidEnrichRequest> requestCaptor = ArgumentCaptor.forClass(PlaidEnrichRequest.class);
        verify(plaidApiClient).enrichTransactions(requestCaptor.capture());

        PlaidEnrichRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.accountId()).isEqualTo(request.accountId());
        assertThat(capturedRequest.transactions()).hasSize(request.transactions().size());
    }

    @Test
    @DisplayName("Should include metadata in enriched response")
    void shouldIncludeMetadataInEnrichedResponse() {
        // Given
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        EnrichmentRequest request = createTestRequest();
        PlaidEnrichResponse plaidResponse = createTestPlaidResponse();

        when(guidGenerator.generate()).thenReturn(requestId);
        when(plaidApiClient.enrichTransactions(any())).thenReturn(Mono.just(plaidResponse));
        when(enrichmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        EnrichmentResponse response = enrichmentService.enrichTransactions(request);

        // Then
        assertThat(response.enrichedTransactions()).isNotEmpty();
        EnrichmentResponse.EnrichedTransaction enriched = response.enrichedTransactions().get(0);
        assertThat(enriched.metadata()).isNotEmpty();
        assertThat(enriched.metadata()).containsKey("categoryId");
        assertThat(enriched.metadata()).containsKey("website");
        assertThat(enriched.metadata()).containsKey("confidenceLevel");
    }

    // Helper methods
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
}
