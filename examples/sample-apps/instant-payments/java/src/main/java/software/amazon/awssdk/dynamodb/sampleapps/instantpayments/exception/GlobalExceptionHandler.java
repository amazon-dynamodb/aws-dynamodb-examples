package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Centralized exception handler that maps domain and common web-layer failures to HTTP error
 * responses using {@link ErrorResponse}.
 *
 * <p>Async MVC controllers return {@code CompletableFuture}. Failures thrown inside
 * {@code thenApply} or {@code thenCompose} chains arrive wrapped in {@link CompletionException}.
 * {@link #handleCompletionException(CompletionException)} unwraps domain and DynamoDB causes
 * before the generic {@link #handleUnexpectedException(Exception)} fallback runs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Conservative {@code Retry-After} hint (seconds) returned with throttling and transient
     * dependency faults. Short because the SDK already retried internally. It only tells a
     * well-behaved client not to hammer the API immediately.
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    /**
     * {@link PaymentNotFoundException} maps to HTTP 404 with {@code PAYMENT_NOT_FOUND}.
     *
     * @param ex domain exception carrying the missing id
     * @return JSON body {@link ErrorResponse} ({@code error}, {@code message}, {@code timestamp})
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex) {
        logger.debug("Payment not found: paymentId={}", ex.getPaymentId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PAYMENT_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link AccountNotFoundException} maps to HTTP 404 with {@code ACCOUNT_NOT_FOUND}.
     *
     * @param ex domain exception carrying the missing id
     * @return JSON body {@link ErrorResponse} ({@code error}, {@code message}, {@code timestamp})
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        logger.debug("Account not found: accountId={}", ex.getAccountId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ACCOUNT_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * {@link IdempotencyConflictException} maps to HTTP 409 with {@code IDEMPOTENCY_CONFLICT}.
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
     * {@link InvalidPaymentStateException} maps to HTTP 400 with {@code INVALID_PAYMENT_STATE}.
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
     * {@link InvalidPaginationTokenException} maps to HTTP 400 with {@code INVALID_PAGINATION_TOKEN}.
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
     * {@link InvalidBatchGetReservationsRequestException} maps to HTTP 400 with
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
     * Bean Validation ({@code jakarta.validation}) failures map to HTTP 400 {@code VALIDATION_ERROR}.
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
     * Bean Validation ({@code jakarta.validation}) failures on path variables and query parameters
     * of a {@code @Validated} controller map to HTTP 400 {@code VALIDATION_ERROR}.
     *
     * <p>Spring raises {@link ConstraintViolationException} from method-level validation, for example
     * when a {@code paymentId}, {@code accountId} or {@code merchantId} path variable exceeds the
     * allowed length or does not match the allowed character pattern. Bounding these values stops an
     * over-long or malformed id from reaching DynamoDB as a partition or GSI key.
     *
     * @param ex method-level constraint violations carrying the offending property paths
     * @return aggregated message listing invalid parameters
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> leafPropertyName(v.getPropertyPath()) + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        logger.warn("Constraint violation: details={}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, Instant.now()));
    }

    /**
     * Spring MVC native method validation failures map to HTTP 400 {@code VALIDATION_ERROR}.
     *
     * <p>When the {@code MethodValidationPostProcessor} AOP proxy is not present (for example in a
     * sliced {@code @WebMvcTest} context), Spring MVC validates method parameters itself and raises
     * {@link HandlerMethodValidationException} instead of {@link ConstraintViolationException}. This
     * handler maps it to the same envelope so the response is identical regardless of which
     * validation path runs.
     *
     * @param ex per-parameter validation results from native method validation
     * @return aggregated message listing invalid parameters
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": "
                                + error.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        logger.warn("Method validation error: details={}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message, Instant.now()));
    }

    /**
     * Malformed JSON or incompatible request body map to HTTP 400 {@code VALIDATION_ERROR}.
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
     * Query or path variable type mismatch (for example non-numeric {@code limit}) map to HTTP 400
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
     * DynamoDB service faults thrown directly (no {@code join()} wrapper) map to distinct error codes so
     * oncall can route on the {@code error} field instead of grepping a generic 500.
     *
     * @param ex DynamoDB service exception
     * @return error envelope mapped by {@link #mapDynamoDbException(DynamoDbException)}
     */
    @ExceptionHandler(DynamoDbException.class)
    public ResponseEntity<ErrorResponse> handleDynamoDbException(DynamoDbException ex) {
        return mapDynamoDbException(ex);
    }

    /**
     * Unwraps domain and DynamoDB failures thrown inside {@code CompletableFuture} composition chains
     * on async MVC controllers.
     *
     * @param ex wrapper from an exceptional async completion
     * @return mapped domain or dependency response, otherwise the generic fallback
     */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<ErrorResponse> handleCompletionException(CompletionException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof PaymentNotFoundException paymentNotFound) {
            return handlePaymentNotFound(paymentNotFound);
        }
        if (cause instanceof AccountNotFoundException accountNotFound) {
            return handleAccountNotFound(accountNotFound);
        }
        if (cause instanceof IdempotencyConflictException idempotencyConflict) {
            return handleIdempotencyConflict(idempotencyConflict);
        }
        if (cause instanceof InvalidPaymentStateException invalidPaymentState) {
            return handleInvalidPaymentState(invalidPaymentState);
        }
        if (cause instanceof InvalidPaginationTokenException invalidPaginationToken) {
            return handleInvalidPaginationToken(invalidPaginationToken);
        }
        if (cause instanceof InvalidBatchGetReservationsRequestException invalidBatchGet) {
            return handleInvalidBatchGetReservationsRequest(invalidBatchGet);
        }
        if (cause instanceof DynamoDbException dynamoDbException) {
            return mapDynamoDbException(dynamoDbException);
        }
        return handleUnexpectedException(ex);
    }

    /**
     * Fallback: returns HTTP 500 without leaking internals. Async controllers surface dependency faults
     * via {@link CompletionException}. Background tasks may still wrap DynamoDB errors in a
     * {@link RuntimeException}. The cause chain is scanned first so those wrapped dependency faults
     * still get a distinct, routable error code rather than a blanket {@code INTERNAL_ERROR}.
     *
     * @param ex any uncaught exception
     * @return mapped DynamoDB error when one is found in the cause chain, otherwise a generic 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception ex) {
        DynamoDbException ddbCause = findDynamoDbException(ex);
        if (ddbCause != null) {
            return mapDynamoDbException(ddbCause);
        }
        logger.error("Unexpected server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }

    /**
     * Maps a concrete DynamoDB service exception to an {@link ErrorResponse} with a distinct,
     * alertable error code.
     *
     * <ul>
     *   <li>{@link ProvisionedThroughputExceededException} maps to 503 {@code THROUGHPUT_EXCEEDED} with
     *       {@code Retry-After}. The table or index is being throttled.</li>
     *   <li>{@link RequestLimitExceededException} maps to 503 {@code REQUEST_LIMIT_EXCEEDED} with
     *       {@code Retry-After}. The account-level request limit was hit.</li>
     *   <li>{@link ResourceNotFoundException} maps to 503 {@code TABLE_NOT_FOUND}. The table is
     *       missing. This is a deploy or config fault, not a client 404.</li>
     *   <li>{@link InternalServerErrorException} maps to 503 {@code DYNAMODB_INTERNAL_ERROR} with
     *       {@code Retry-After}. This is a transient DynamoDB-side fault.</li>
     * </ul>
     *
     * @param ex DynamoDB service exception (already unwrapped from any async wrapper)
     * @return error envelope with the matching status, code and (for transient faults) a Retry-After header
     */
    private ResponseEntity<ErrorResponse> mapDynamoDbException(DynamoDbException ex) {
        if (ex instanceof ProvisionedThroughputExceededException) {
            logger.warn("DynamoDB provisioned throughput exceeded", ex);
            return throttled("THROUGHPUT_EXCEEDED",
                    "Request rate exceeded provisioned throughput. Retry after a short delay");
        }
        if (ex instanceof RequestLimitExceededException) {
            logger.warn("DynamoDB request limit exceeded", ex);
            return throttled("REQUEST_LIMIT_EXCEEDED",
                    "Account request limit exceeded. Retry after a short delay");
        }
        if (ex instanceof ResourceNotFoundException) {
            logger.error("DynamoDB resource not found (table missing or being created?)", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("TABLE_NOT_FOUND",
                            "A required DynamoDB resource is unavailable", Instant.now()));
        }
        if (ex instanceof InternalServerErrorException) {
            logger.error("DynamoDB internal server error", ex);
            return throttled("DYNAMODB_INTERNAL_ERROR",
                    "DynamoDB reported an internal error. Retry after a short delay");
        }
        logger.error("Unhandled DynamoDB error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }

    /**
     * Builds a 503 response with a {@code Retry-After} header for throttling and transient faults.
     *
     * @param code    machine-readable error code
     * @param message human-readable description
     * @return 503 error envelope carrying {@code Retry-After}
     */
    private ResponseEntity<ErrorResponse> throttled(String code, String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(new ErrorResponse(code, message, Instant.now()));
    }

    /**
     * Walks the cause chain looking for a {@link DynamoDbException}, since {@code join()} wraps
     * dependency faults in {@link CompletionException} (read paths) or a
     * rethrown {@code RuntimeException} (write paths).
     *
     * @param throwable the top-level exception caught by the fallback handler
     * @return the first {@link DynamoDbException} in the cause chain, or {@code null} if none
     */
    private DynamoDbException findDynamoDbException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DynamoDbException ddb) {
                return ddb;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Extracts the leaf node of a {@link Path}, so a violation reported as {@code getAccount.accountId}
     * is surfaced to the client as just {@code accountId}, matching the field naming used by the
     * request-body validation handler.
     *
     * @param propertyPath the constraint violation property path
     * @return the last path node name, or the full path string when no nodes are present
     */
    private String leafPropertyName(Path propertyPath) {
        String leaf = null;
        for (Path.Node node : propertyPath) {
            leaf = node.getName();
        }
        return leaf != null ? leaf : propertyPath.toString();
    }
}
