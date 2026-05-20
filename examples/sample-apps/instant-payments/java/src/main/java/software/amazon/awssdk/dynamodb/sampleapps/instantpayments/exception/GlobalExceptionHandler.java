package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;

/**
 * Centralized exception handler that maps domain and common web-layer failures to HTTP error
 * responses using {@link ErrorResponse}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * {@link PaymentNotFoundException} → HTTP 404 with {@code PAYMENT_NOT_FOUND}.
     *
     * @param ex domain exception carrying the missing id
     * @return JSON body {@link ErrorResponse} ({@code error}, {@code message}, {@code timestamp})
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex) {
        logger.warn("Payment not found: paymentId={}", ex.getPaymentId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PAYMENT_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link AccountNotFoundException} → HTTP 404 with {@code ACCOUNT_NOT_FOUND}.
     *
     * @param ex domain exception carrying the missing id
     * @return JSON body {@link ErrorResponse} ({@code error}, {@code message}, {@code timestamp})
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        logger.warn("Account not found: accountId={}", ex.getAccountId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ACCOUNT_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link IdempotencyConflictException} → HTTP 409 with {@code IDEMPOTENCY_CONFLICT}.
     *
     * @param ex duplicate key with mismatched payload hash
     * @return error envelope
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        logger.warn("Idempotency conflict: idempotencyKey={}", ex.getIdempotencyKey());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("IDEMPOTENCY_CONFLICT", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link InvalidPaymentStateException} → HTTP 400 with {@code INVALID_PAYMENT_STATE}.
     *
     * @param ex unrecognised state value from a path parameter
     * @return error envelope
     */
    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaymentState(InvalidPaymentStateException ex) {
        logger.warn("Invalid payment state: state={}", ex.getInvalidState());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PAYMENT_STATE", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link InvalidPaginationTokenException} → HTTP 400 with {@code INVALID_PAGINATION_TOKEN}.
     *
     * @param ex invalid opaque pagination token supplied by the client
     * @return error envelope
     */
    @ExceptionHandler(InvalidPaginationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaginationToken(InvalidPaginationTokenException ex) {
        logger.warn("Invalid pagination token supplied");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PAGINATION_TOKEN", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link InvalidBatchGetReservationsRequestException} → HTTP 400 with
     * {@code INVALID_BATCH_GET_RESERVATIONS_REQUEST}.
     *
     * @param ex semantic validation failure for the batch-get reservations API
     * @return error envelope
     */
    @ExceptionHandler(InvalidBatchGetReservationsRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBatchGetReservationsRequest(
            InvalidBatchGetReservationsRequestException ex) {
        logger.warn("Invalid batch-get reservations request: reason={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_BATCH_GET_RESERVATIONS_REQUEST", ex.getMessage(), Instant.now()));
    }

    /**
     * Bean Validation ({@code jakarta.validation}) failures → HTTP 400 {@code VALIDATION_ERROR}.
     *
     * @param ex binding field errors from the failed request body
     * @return aggregated message listing invalid fields
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        logger.warn("Validation error: details={}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, Instant.now()));
    }

    /**
     * Malformed JSON or incompatible request body → HTTP 400 {@code VALIDATION_ERROR}.
     *
     * @param ex message conversion could not deserialize the body
     * @return error envelope matching other client error responses
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableHttpMessage(HttpMessageNotReadableException ex) {
        logger.warn("Unreadable HTTP request body: reason={}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", "Request body is not valid JSON", Instant.now()));
    }

    /**
     * Query or path variable type mismatch (for example non-numeric {@code limit}) → HTTP 400
     * {@code VALIDATION_ERROR}.
     *
     * @param ex binding failed for a single request value
     * @return error envelope with parameter name and rejected value
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String valuePart = ex.getValue() != null ? String.valueOf(ex.getValue()) : "null";
        String message = "Invalid value for '%s': %s".formatted(ex.getName(), valuePart);
        logger.warn("Request parameter type mismatch: details={}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, Instant.now()));
    }

    /**
     * Browsers request {@code /favicon.ico} even when the app does not ship a favicon. Without this
     * handler, the static-resource handler throws and pollutes logs. Returns HTTP 204 for that path only.
     *
     * @param ex resource path was not found under configured static locations
     * @return empty 204 for favicon, otherwise HTTP 404 with {@link ErrorResponse}
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException ex) {
        String path = ex.getResourcePath();
        if (path != null && path.endsWith("favicon.ico")) {
            return ResponseEntity.noContent().build();
        }
        logger.debug("Static resource not found: path={}", path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "Resource not found", Instant.now()));
    }

    /**
     * Fallback: logs stack trace and returns HTTP 500 without leaking internals.
     *
     * @param ex any uncaught exception
     * @return generic internal error body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        logger.error("Unexpected server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
