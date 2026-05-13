package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;

/**
 * Centralized exception handler that maps domain exceptions to HTTP error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * {@link PaymentNotFoundException} → HTTP 404 with {@code PAYMENT_NOT_FOUND}.
     *
     * @param ex domain exception carrying the missing id
     * @return JSON body {@link ErrorResponse} ({@code error}, {@code message}, {@code timestamp})
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex) {
        log.warn("Payment not found: {}", ex.getPaymentId());
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
        log.warn("Account not found: {}", ex.getAccountId());
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
        log.warn("Idempotency conflict: {}", ex.getMessage());
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
        log.warn("Invalid payment state: {}", ex.getInvalidState());
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
        log.warn("Invalid pagination token supplied");
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
        log.warn("Invalid batch-get reservations request: {}", ex.getMessage());
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
        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, Instant.now()));
    }

    /**
     * Fallback: logs stack trace and returns HTTP 500 without leaking internals.
     *
     * @param ex any uncaught exception
     * @return generic internal error body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
