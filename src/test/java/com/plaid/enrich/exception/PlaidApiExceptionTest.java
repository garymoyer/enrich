package com.plaid.enrich.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlaidApiException Unit Tests")
class PlaidApiExceptionTest {

    @Test
    @DisplayName("Should store status code")
    void shouldStoreStatusCode() {
        PlaidApiException ex = new PlaidApiException("error", 503);
        assertThat(ex.getStatusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("Should store plaid error code")
    void shouldStorePlaidErrorCode() {
        PlaidApiException ex = new PlaidApiException("error", 503, "PLAID_ERROR_001");
        assertThat(ex.getPlaidErrorCode()).isEqualTo("PLAID_ERROR_001");
    }

    @Test
    @DisplayName("Should default to zero status code and null error code when only message supplied")
    void shouldDefaultsWhenOnlyMessageSupplied() {
        PlaidApiException ex = new PlaidApiException("error");
        assertThat(ex.getStatusCode()).isZero();
        assertThat(ex.getPlaidErrorCode()).isNull();
    }

    @Test
    @DisplayName("Should include status code and error code in toString")
    void shouldIncludeFieldsInToString() {
        PlaidApiException ex = new PlaidApiException("service error", 502, "INVALID_REQUEST");
        String str = ex.toString();
        assertThat(str).contains("service error");
        assertThat(str).contains("502");
        assertThat(str).contains("INVALID_REQUEST");
    }
}
