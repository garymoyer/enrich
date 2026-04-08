package com.td.enrich.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralizes all exception-to-HTTP-response translation for the service.
 *
 * <p>Without this class, unhandled exceptions bubble up to Spring's default error
 * handler, which returns a generic 500 response that gives callers no useful
 * information. By declaring {@code @ExceptionHandler} methods here, we intercept
 * specific exception types and return structured error responses before Spring's
 * default handler ever runs.
 *
 * <p>{@code @RestControllerAdvice} means this class applies globally to every
 * {@code @RestController} in the application. The {@code @ExceptionHandler} methods
 * inside it are matched by exception type — Spring picks the most specific matching
 * method for each thrown exception.
 *
 * <p>All responses follow <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807
 * Problem Detail</a> format, which looks like:
 * <pre>{@code
 * {
 *   "type":      "https://plaid.com/docs/errors",
 *   "title":     "Plaid API Error",
 *   "status":    502,
 *   "detail":    "Error communicating with Plaid API: 503 Service Unavailable",
 *   "timestamp": "2026-04-07T15:30:00Z"
 * }
 * }</pre>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles failures from the Plaid API integration layer.
     *
     * <p>Returns HTTP 502 Bad Gateway because the failure is in our upstream
     * dependency (Plaid), not in the client's request. The response body includes
     * the Plaid HTTP status code and, when available, Plaid's own error code string
     * so support teams can look it up in Plaid's documentation.
     *
     * @param ex      the Plaid exception thrown by {@link com.td.enrich.service.PlaidApiClient}
     * @param request the current HTTP request (used by Spring for context; not used here)
     * @return a 502 response with Plaid error details in RFC 7807 format
     */
    @ExceptionHandler(PlaidApiException.class)
    public ResponseEntity<ProblemDetail> handlePlaidApiException(
            PlaidApiException ex,
            WebRequest request) {

        // Log at ERROR so this shows up in alerts — a Plaid failure affects real users
        log.error("Plaid API error: statusCode={}, plaidErrorCode={}, message={}",
                ex.getStatusCode(), ex.getPlaidErrorCode(), ex.getMessage(), ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "Error communicating with Plaid API: " + ex.getMessage()
        );
        problemDetail.setTitle("Plaid API Error");
        problemDetail.setType(URI.create("https://plaid.com/docs/errors"));
        problemDetail.setProperty("timestamp", OffsetDateTime.now());
        problemDetail.setProperty("plaidStatusCode", ex.getStatusCode());

        // Only include plaidErrorCode if Plaid sent one — avoids cluttering the response with nulls
        if (ex.getPlaidErrorCode() != null) {
            problemDetail.setProperty("plaidErrorCode", ex.getPlaidErrorCode());
        }

        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problemDetail);
    }

    /**
     * Handles Bean Validation failures triggered by {@code @Valid} on controller
     * method parameters.
     *
     * <p>When a client sends a request that fails validation (e.g. missing
     * {@code accountId}, negative {@code amount}), Spring throws
     * {@link MethodArgumentNotValidException} before the controller method body runs.
     * This handler collects all field-level errors and returns them in the response
     * so the client knows exactly what to fix.
     *
     * <p>Returns HTTP 400 Bad Request.
     *
     * @param ex      contains the list of all validation failures
     * @param request the current HTTP request
     * @return a 400 response with a map of {@code fieldName → errorMessage} pairs
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException ex,
            WebRequest request) {

        // WARN because this is a client error, not a service bug
        log.warn("Request validation failed: {}", ex.getMessage());

        // Collect every field error into a map so the client gets all problems at once,
        // not just the first one encountered
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Validation failed for one or more fields"
        );
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setProperty("timestamp", OffsetDateTime.now());
        problemDetail.setProperty("errors", fieldErrors); // e.g. { "amount": "must be positive" }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /**
     * Handles programmatic input validation failures thrown by service or utility code
     * (e.g. {@code throw new IllegalArgumentException("Invalid GUID format")}).
     *
     * <p>Returns HTTP 400 Bad Request.
     *
     * @param ex      the illegal argument exception with a descriptive message
     * @param request the current HTTP request
     * @return a 400 response with the exception message as the detail
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request) {

        log.warn("Invalid argument in request: {}", ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problemDetail.setTitle("Invalid Request");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setProperty("timestamp", OffsetDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    /**
     * Safety net that catches any exception not handled by the more specific methods above.
     *
     * <p>Returns HTTP 500 Internal Server Error with a generic message. The actual
     * exception is logged at ERROR level with the full stack trace so engineers can
     * investigate via logs, but it is deliberately not exposed in the response body —
     * internal error details could reveal implementation information to attackers.
     *
     * @param ex      the unhandled exception
     * @param request the current HTTP request
     * @return a 500 response with a generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(
            Exception ex,
            WebRequest request) {

        // Log with full stack trace — this indicates an unplanned failure
        log.error("Unexpected error handling request", ex);

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later."
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("about:blank"));
        problemDetail.setProperty("timestamp", OffsetDateTime.now());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }
}
