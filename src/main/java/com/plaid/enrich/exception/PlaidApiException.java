package com.plaid.enrich.exception;

/**
 * Exception thrown when Plaid API interactions fail.
 * Wraps various HTTP client errors and Plaid-specific errors.
 */
public class PlaidApiException extends RuntimeException {

    private final int statusCode;
    private final String plaidErrorCode;

    public PlaidApiException(String message) {
        super(message);
        this.statusCode = 0;
        this.plaidErrorCode = null;
    }

    public PlaidApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.plaidErrorCode = null;
    }

    public PlaidApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
        this.plaidErrorCode = null;
    }

    public PlaidApiException(String message, int statusCode, String plaidErrorCode) {
        super(message);
        this.statusCode = statusCode;
        this.plaidErrorCode = plaidErrorCode;
    }

    public PlaidApiException(String message, int statusCode, String plaidErrorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.plaidErrorCode = plaidErrorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

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
