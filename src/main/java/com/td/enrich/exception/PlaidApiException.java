package com.td.enrich.exception;

/**
 * Thrown whenever communication with the Plaid API fails.
 *
 * <p>This exception is the single error type that propagates out of
 * {@link com.td.enrich.service.PlaidApiClient}. It wraps the three pieces of
 * information we need to diagnose a Plaid failure:
 * <ol>
 *   <li>A human-readable message (from the standard {@link RuntimeException}).</li>
 *   <li>The HTTP status code Plaid returned (e.g. 400, 500, 503).</li>
 *   <li>Plaid's own error code string if one was present in the response body
 *       (e.g. "INVALID_INPUT", "INTERNAL_SERVER_ERROR").</li>
 * </ol>
 *
 * <p>The {@link com.td.enrich.exception.GlobalExceptionHandler} catches this exception
 * at the controller layer and converts it into an RFC 7807 Problem Detail response
 * with HTTP status 502 Bad Gateway (because the failure is in our upstream dependency,
 * not in the client's request).
 *
 * <p>The Resilience4j retry policy uses a predicate to decide whether to retry:
 * exceptions with a 5xx {@code statusCode} are retried; 4xx exceptions are not
 * (a bad-request error won't fix itself on a second attempt).
 *
 * <p>Extends {@link RuntimeException} (unchecked) so callers in the reactive pipeline
 * don't have to declare {@code throws PlaidApiException} everywhere.
 */
public class PlaidApiException extends RuntimeException {

    /**
     * The HTTP status code returned by Plaid, or {@code 0} when the failure happened
     * before an HTTP response was received (e.g. a connection timeout).
     */
    private final int statusCode;

    /**
     * Plaid-specific error code extracted from the response body JSON, if present.
     * {@code null} when Plaid didn't include one (e.g. network-level failures).
     */
    private final String plaidErrorCode;

    /**
     * Use when no HTTP response was received (e.g. timeout, connection refused).
     * Sets {@code statusCode = 0} and {@code plaidErrorCode = null}.
     *
     * @param message description of what failed
     */
    public PlaidApiException(String message) {
        super(message);
        this.statusCode = 0;
        this.plaidErrorCode = null;
    }

    /**
     * Use when wrapping a lower-level exception (e.g. a reactive pipeline error)
     * that has no HTTP status associated with it.
     *
     * @param message description of what failed
     * @param cause   the underlying exception that triggered this one
     */
    public PlaidApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.plaidErrorCode = null;
    }

    /**
     * Use when Plaid returned an HTTP error response (4xx or 5xx).
     * Sets {@code plaidErrorCode = null} — call the four-argument constructor if
     * the response body also contained a Plaid error code.
     *
     * @param message    description of the error
     * @param statusCode the HTTP status code from Plaid's response
     */
    public PlaidApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.plaidErrorCode = null;
    }

    /**
     * Use when Plaid returned a structured error body containing its own error code.
     *
     * @param message        description of the error
     * @param statusCode     the HTTP status code from Plaid's response
     * @param plaidErrorCode Plaid's error code string (e.g. "INVALID_INPUT")
     */
    public PlaidApiException(String message, int statusCode, String plaidErrorCode) {
        super(message);
        this.statusCode = statusCode;
        this.plaidErrorCode = plaidErrorCode;
    }

    /**
     * Full constructor: HTTP status, Plaid error code, and an underlying cause.
     * Use when wrapping a {@link org.springframework.web.reactive.function.client.WebClientResponseException}
     * that carries both a status and a cause.
     *
     * @param message        description of the error
     * @param statusCode     the HTTP status code from Plaid's response
     * @param plaidErrorCode Plaid's error code string, or {@code null}
     * @param cause          the underlying exception
     */
    public PlaidApiException(String message, int statusCode, String plaidErrorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.plaidErrorCode = plaidErrorCode;
    }

    /**
     * Returns the HTTP status code Plaid returned, or {@code 0} if no HTTP response
     * was received. The retry predicate in
     * {@link com.td.enrich.service.PlaidApiClient} checks this: values ≥ 500 are
     * retried; values in the 400–499 range are not.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the Plaid-specific error code from the response body, or {@code null}
     * if Plaid didn't include one. Included in the 502 response body so support
     * teams can cross-reference Plaid's own error documentation.
     */
    public String getPlaidErrorCode() {
        return plaidErrorCode;
    }

    @Override
    public String toString() {
        return "PlaidApiException{" +
               "message='" + getMessage() + '\'' +
               ", statusCode=" + statusCode +
               ", plaidErrorCode='" + plaidErrorCode + '\'' +
               '}';
    }
}
