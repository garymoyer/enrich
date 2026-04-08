package com.td.enrich.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PlaidApiException}.
 *
 * <p>These tests verify that every constructor correctly stores its arguments and
 * that the {@link PlaidApiException#toString()} method includes all diagnostic fields.
 *
 * <p>There is no mocking here — {@link PlaidApiException} is a plain exception class
 * with no dependencies, so the tests simply construct instances and assert on their state.
 */
@DisplayName("PlaidApiException Unit Tests")
class PlaidApiExceptionTest {

    @Test
    @DisplayName("Should store status code")
    void shouldStoreStatusCode() {
        // The two-arg constructor (message + statusCode) is the most common usage
        PlaidApiException ex = new PlaidApiException("error", 503);
        assertThat(ex.getStatusCode()).isEqualTo(503);
    }

    @Test
    @DisplayName("Should store plaid error code")
    void shouldStorePlaidErrorCode() {
        // Three-arg constructor adds the Plaid-specific error code string
        PlaidApiException ex = new PlaidApiException("error", 503, "PLAID_ERROR_001");
        assertThat(ex.getPlaidErrorCode()).isEqualTo("PLAID_ERROR_001");
    }

    @Test
    @DisplayName("Should default to zero status code and null error code when only message supplied")
    void shouldDefaultsWhenOnlyMessageSupplied() {
        // One-arg constructor: used when no HTTP response was received (e.g. timeout)
        PlaidApiException ex = new PlaidApiException("error");
        assertThat(ex.getStatusCode()).isZero();      // 0 means "no HTTP status"
        assertThat(ex.getPlaidErrorCode()).isNull();
    }

    @Test
    @DisplayName("Should include status code and error code in toString")
    void shouldIncludeFieldsInToString() {
        // toString() output is written to logs; all three diagnostic fields must appear
        PlaidApiException ex = new PlaidApiException("service error", 502, "INVALID_REQUEST");
        String str = ex.toString();
        assertThat(str).contains("service error");
        assertThat(str).contains("502");
        assertThat(str).contains("INVALID_REQUEST");
    }

    @Test
    @DisplayName("Should wrap a cause with message only")
    void shouldWrapCauseWithMessageOnly() {
        // Two-arg constructor (message + cause): wraps a lower-level exception without
        // a known HTTP status — statusCode defaults to 0, plaidErrorCode defaults to null
        Throwable cause = new RuntimeException("root cause");
        PlaidApiException ex = new PlaidApiException("error", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getStatusCode()).isZero();
        assertThat(ex.getPlaidErrorCode()).isNull();
    }

    @Test
    @DisplayName("Should wrap a cause with all fields")
    void shouldWrapCauseWithAllFields() {
        // Four-arg constructor: full information — message, HTTP status, Plaid error code, cause
        Throwable cause = new RuntimeException("root cause");
        PlaidApiException ex = new PlaidApiException("error", 503, "PLAID_ERROR_002", cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getStatusCode()).isEqualTo(503);
        assertThat(ex.getPlaidErrorCode()).isEqualTo("PLAID_ERROR_002");
    }
}
