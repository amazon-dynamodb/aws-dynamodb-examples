package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

import java.time.Instant;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Translates domain exceptions into consistent HTTP error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Conservative {@code Retry-After} hint in seconds returned with throttling and transient
     * DynamoDB faults. The value is short because the SDK already retried internally. It only tells
     * a well-behaved client not to hammer the API immediately.
     */
    private static final String RETRY_AFTER_SECONDS = "1";

    /**
     * Maps {@link InvalidPaginationTokenException} to HTTP 400 with code {@code INVALID_PAGINATION_TOKEN}.
     *
     * @param ex invalid opaque pagination token supplied by the client
     * @return JSON error envelope
     */
    @ExceptionHandler(InvalidPaginationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPaginationToken(InvalidPaginationTokenException ex) {
        logger.warn("Client supplied an invalid pagination token [errorCode=INVALID_PAGINATION_TOKEN]");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_PAGINATION_TOKEN", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link InvalidEventAttributesException} to HTTP 400 with code {@code INVALID_EVENT_ATTRIBUTES}.
     *
     * <p>Raised when an event is missing an attribute its type requires, for example a {@code PVP_MATCH}
     * without a numeric {@code playerScore}. Rejecting the request stops a non-projecting event from
     * being persisted silently.
     *
     * @param ex describes the missing or invalid event attribute
     * @return JSON error envelope
     */
    @ExceptionHandler(InvalidEventAttributesException.class)
    public ResponseEntity<ErrorResponse> handleInvalidEventAttributes(InvalidEventAttributesException ex) {
        logger.warn("Event attributes failed validation [errorCode=INVALID_EVENT_ATTRIBUTES, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_EVENT_ATTRIBUTES", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link PlayerNotFoundException} to HTTP 404.
     *
     * @param ex contains the missing player id
     * @return JSON error envelope
     */
    @ExceptionHandler(PlayerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePlayerNotFound(PlayerNotFoundException ex) {
        logger.debug("Player profile not found [errorCode=PLAYER_NOT_FOUND, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PLAYER_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link WalletNotFoundException} to HTTP 404.
     *
     * @param ex contains the player id whose wallet row is missing
     * @return JSON error envelope
     */
    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex) {
        logger.debug("Player wallet not found [errorCode=WALLET_NOT_FOUND, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("WALLET_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link PlayerAlreadyExistsException} to HTTP 409.
     *
     * @param ex contains the conflicting player id
     * @return JSON error envelope
     */
    @ExceptionHandler(PlayerAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handlePlayerAlreadyExists(PlayerAlreadyExistsException ex) {
        logger.warn("Player registration conflict [errorCode=PLAYER_ALREADY_EXISTS, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("PLAYER_ALREADY_EXISTS", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link InsufficientFundsException} to HTTP 409.
     *
     * @param ex contains player id and balance detail
     * @return JSON error envelope
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        logger.warn("Purchase rejected due to insufficient balance [errorCode=INSUFFICIENT_FUNDS, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps {@link StaleVersionException} to HTTP 409 for optimistic locking conflicts.
     *
     * @param ex contains player id and expected version
     * @return JSON error envelope
     */
    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ErrorResponse> handleStaleVersion(StaleVersionException ex) {
        logger.warn("Optimistic lock conflict [errorCode=STALE_VERSION, playerId={}, expectedVersion={}]",
                ex.getPlayerId(), ex.getExpectedVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("STALE_VERSION", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps Jakarta Bean Validation failures from {@code @Valid} request bodies to HTTP 400.
     *
     * @param ex binding and field errors from the failed request
     * @return JSON error envelope
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        if (detail.isBlank()) {
            detail = "Validation failed";
        }
        logger.warn("Request validation failed [errorCode=VALIDATION_ERROR, detail={}]",
                detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", detail, Instant.now()));
    }

    /**
     * Maps Jakarta Bean Validation failures on path variables and query parameters of a
     * {@code @Validated} controller to HTTP 400 {@code VALIDATION_ERROR}.
     *
     * <p>Spring raises {@link ConstraintViolationException} from method-level validation, for example
     * when a {@code playerId}, {@code scope} or {@code platform} path variable exceeds the allowed
     * length or does not match the allowed character pattern. Bounding these values stops an over-long
     * or malformed id from reaching DynamoDB as a partition or index key.
     *
     * @param ex method-level constraint violations carrying the offending property paths
     * @return JSON error envelope listing the invalid parameters
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> leafPropertyName(v.getPropertyPath()) + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        if (detail.isBlank()) {
            detail = "Validation failed";
        }
        logger.warn("Path or query parameter constraint violation [errorCode=VALIDATION_ERROR, detail={}]",
                detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", detail, Instant.now()));
    }

    /**
     * Maps Spring MVC native method validation failures to HTTP 400 {@code VALIDATION_ERROR}.
     *
     * <p>When the {@code MethodValidationPostProcessor} AOP proxy is not present, for example in a
     * standalone {@code MockMvc} setup, Spring MVC validates method parameters itself and raises
     * {@link HandlerMethodValidationException} instead of {@link ConstraintViolationException}. This
     * handler maps it to the same envelope so the response is identical regardless of which validation
     * path runs.
     *
     * @param ex per-parameter validation results from native method validation
     * @return JSON error envelope listing the invalid parameters
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        String detail = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": "
                                + error.getDefaultMessage()))
                .collect(Collectors.joining(", "));
        if (detail.isBlank()) {
            detail = "Validation failed";
        }
        logger.warn("Method parameter validation failed [errorCode=VALIDATION_ERROR, detail={}]",
                detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", detail, Instant.now()));
    }

    /**
     * Maps an unreadable or malformed request body to HTTP 400.
     *
     * <p>Spring raises {@link HttpMessageNotReadableException} while deserializing the request body.
     * This happens <em>before</em> {@code @Valid} bean validation runs, so without this handler the
     * exception falls through to the generic {@link Exception} handler and is reported as HTTP 500.
     * Three distinct client errors are surfaced with their own codes:
     *
     * <ul>
     *   <li>{@code VALIDATION_ERROR} when the JSON is syntactically valid but a value cannot be bound
     *       to the target type, for example an unknown enum constant such as
     *       {@code reason=NOT_A_REASON} or a non-numeric value for a {@code long} field. The message
     *       names the offending field.</li>
     *   <li>{@code MALFORMED_REQUEST_BODY} when a body was sent but is not parseable as JSON, for
     *       example truncated or syntactically invalid JSON.</li>
     *   <li>{@code MISSING_REQUEST_BODY} when no request body was sent at all. Note that an empty JSON
     *       object body is present and valid, so it instead fails later with
     *       {@code VALIDATION_ERROR} on the {@code @NotBlank} fields.</li>
     * </ul>
     *
     * @param ex the body-parsing failure raised by the configured {@code HttpMessageConverter}
     * @return JSON error envelope
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife) {
            String detail = describeInvalidValue(ife);
            logger.warn("Request body contained an invalid value [errorCode=VALIDATION_ERROR, detail={}]",
                    detail);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("VALIDATION_ERROR", detail, Instant.now()));
        }
        if (cause instanceof JsonProcessingException) {
            logger.warn("Request body is not valid JSON [errorCode=MALFORMED_REQUEST_BODY, detail={}]",
                    ex.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("MALFORMED_REQUEST_BODY",
                            "Request body is not valid JSON", Instant.now()));
        }
        logger.warn("Request body is absent [errorCode=MISSING_REQUEST_BODY]");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MISSING_REQUEST_BODY",
                        "Request body is required", Instant.now()));
    }

    /**
     * Builds a client-facing message for a value that parsed as JSON but could not be bound to the
     * target field type. Names the offending field and, when the target is an enum, lists the
     * accepted constants so the caller can correct the request.
     *
     * @param ife Jackson mismatch carrying the field path, offending value and target type
     * @return human-readable validation detail
     */
    private String describeInvalidValue(InvalidFormatException ife) {
        String field = ife.getPath().stream()
                .map(JsonMappingException.Reference::getFieldName)
                .filter(name -> name != null)
                .reduce((first, second) -> second)
                .orElse("requestBody");
        return invalidValueDetail(field);
    }

    /**
     * Builds a uniform {@code "field: invalid value"} detail for a value that could not be bound to
     * its target type. The accepted values are intentionally not listed so the response does not
     * disclose the allowed set (for example the valid enum constants). Shared by the request-body
     * ({@link InvalidFormatException}) and query/path-parameter
     * ({@link MethodArgumentTypeMismatchException}) mismatch handlers so both surface identical
     * messages.
     *
     * @param field the offending field or parameter name
     * @return human-readable validation detail
     */
    private String invalidValueDetail(String field) {
        return field + ": invalid value";
    }

    /**
     * Maps a query or path parameter that cannot be converted to the declared type to HTTP 400
     * {@code VALIDATION_ERROR}.
     *
     * <p>Spring raises {@link MethodArgumentTypeMismatchException} when, for example, {@code ?limit=abc}
     * is sent for an {@code int} parameter or {@code ?scanIndexForward=maybe} for a {@code Boolean}.
     * Without this handler the exception falls through to the generic {@link Exception} handler and is
     * reported as HTTP 500, even though it is a client error. This mirrors the request-body mismatch
     * mapping so a bad value reports the same way regardless of where it appears.
     *
     * @param ex carries the parameter name, offending value and required type
     * @return JSON error envelope naming the offending parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = invalidValueDetail(ex.getName());
        logger.warn("Request parameter could not be bound [errorCode=VALIDATION_ERROR, detail={}]",
                detail);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", detail, Instant.now()));
    }

    /**
     * Maps an unsupported HTTP method to HTTP 405 {@code METHOD_NOT_ALLOWED}.
     *
     * <p>Spring raises {@link HttpRequestMethodNotSupportedException} when a known path is called with
     * a verb it does not support, for example {@code DELETE /api/v1/players}. Without this handler the
     * catch-all {@link Exception} mapping would mask the correct 405 as a 500.
     *
     * @param ex carries the offending method and the methods the handler does support
     * @return JSON error envelope, including an {@code Allow} header when Spring supplies one
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        logger.warn("Unsupported HTTP method [errorCode=METHOD_NOT_ALLOWED, detail={}]", ex.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(new ErrorResponse("METHOD_NOT_ALLOWED", ex.getMessage(), Instant.now()));
    }

    /**
     * Maps an unsupported request {@code Content-Type} to HTTP 415 {@code UNSUPPORTED_MEDIA_TYPE}.
     *
     * <p>Spring raises {@link HttpMediaTypeNotSupportedException} when a POST/PATCH body is sent with a
     * content type the endpoint cannot read, for example {@code text/plain} instead of
     * {@code application/json}, or with no {@code Content-Type} at all. Without this handler the
     * catch-all {@link Exception} mapping would mask the correct 415 as a 500.
     *
     * @param ex carries the offending media type
     * @return JSON error envelope
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        logger.warn("Unsupported media type [errorCode=UNSUPPORTED_MEDIA_TYPE, detail={}]", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE",
                        "Content-Type must be application/json", Instant.now()));
    }

    /**
     * Maps {@link IllegalArgumentException} to HTTP 400 for bad arguments such as invalid path values.
     *
     * @param ex the illegal argument detail
     * @return JSON error envelope
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Invalid request argument [errorCode=INVALID_ARGUMENT, detail={}]",
                ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_ARGUMENT", ex.getMessage(), Instant.now()));
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
     * Maps DynamoDB service faults thrown directly to distinct, alertable error codes so oncall can
     * route on the {@code error} field instead of grepping a generic 500.
     *
     * @param ex DynamoDB service exception
     * @return error envelope mapped by {@link #mapDynamoDbException(DynamoDbException)}
     */
    @ExceptionHandler(DynamoDbException.class)
    public ResponseEntity<ErrorResponse> handleDynamoDbException(DynamoDbException ex) {
        return mapDynamoDbException(ex);
    }

    /**
     * Fallback handler for unexpected exceptions. Maps to HTTP 500 without leaking internals.
     *
     * <p>Service code calls DynamoDB through {@code CompletableFuture.join()}, which wraps a
     * dependency fault in a {@link CompletionException}. The cause chain is
     * scanned first so a wrapped DynamoDB fault still gets a distinct, routable error code rather than
     * a blanket {@code INTERNAL_ERROR}.
     *
     * @param ex the unexpected error
     * @return mapped DynamoDB error when one is found in the cause chain, otherwise a generic 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        DynamoDbException ddbCause = findDynamoDbException(ex);
        if (ddbCause != null) {
            return mapDynamoDbException(ddbCause);
        }
        logger.error("Unhandled exception during request processing [errorCode=INTERNAL_ERROR, detail={}]",
                ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }

    /**
     * Maps a concrete DynamoDB service exception to an {@link ErrorResponse} with a distinct,
     * alertable error code.
     *
     * <ul>
     *   <li>{@link ProvisionedThroughputExceededException} maps to 503 {@code THROUGHPUT_EXCEEDED}
     *       with {@code Retry-After}. The table or index is being throttled.</li>
     *   <li>{@link RequestLimitExceededException} maps to 503 {@code REQUEST_LIMIT_EXCEEDED} with
     *       {@code Retry-After}. The account-level request limit was hit.</li>
     *   <li>{@link ResourceNotFoundException} maps to 503 {@code TABLE_NOT_FOUND}. The table is
     *       missing. This is a deploy or config fault, not a client 404.</li>
     *   <li>{@link InternalServerErrorException} maps to 503 {@code DYNAMODB_INTERNAL_ERROR} with
     *       {@code Retry-After}. This is a transient DynamoDB-side fault.</li>
     * </ul>
     *
     * <p>Any other {@link DynamoDbException} falls back to 500 {@code INTERNAL_ERROR}.
     *
     * @param ex DynamoDB service exception already unwrapped from any async wrapper
     * @return error envelope with the matching status, code and, for transient faults, a Retry-After header
     */
    private ResponseEntity<ErrorResponse> mapDynamoDbException(DynamoDbException ex) {
        if (ex instanceof ProvisionedThroughputExceededException) {
            logger.warn("DynamoDB provisioned throughput exceeded [errorCode=THROUGHPUT_EXCEEDED]", ex);
            return throttled("THROUGHPUT_EXCEEDED",
                    "Request rate exceeded provisioned throughput. Retry after a short delay");
        }
        if (ex instanceof RequestLimitExceededException) {
            logger.warn("DynamoDB request limit exceeded [errorCode=REQUEST_LIMIT_EXCEEDED]", ex);
            return throttled("REQUEST_LIMIT_EXCEEDED",
                    "Account request limit exceeded. Retry after a short delay");
        }
        if (ex instanceof ResourceNotFoundException) {
            logger.error("DynamoDB resource not found, table missing or being created [errorCode=TABLE_NOT_FOUND]", ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("TABLE_NOT_FOUND",
                            "A required DynamoDB resource is unavailable", Instant.now()));
        }
        if (ex instanceof InternalServerErrorException) {
            logger.error("DynamoDB internal server error [errorCode=DYNAMODB_INTERNAL_ERROR]", ex);
            return throttled("DYNAMODB_INTERNAL_ERROR",
                    "DynamoDB reported an internal error. Retry after a short delay");
        }
        logger.error("Unhandled DynamoDB error [errorCode=INTERNAL_ERROR]", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }

    /**
     * Builds a 503 response with a {@code Retry-After} header for throttling and transient faults.
     *
     * @param code machine-readable error code
     * @param message human-readable description
     * @return 503 error envelope carrying {@code Retry-After}
     */
    private ResponseEntity<ErrorResponse> throttled(String code, String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(new ErrorResponse(code, message, Instant.now()));
    }

    /**
     * Walks the cause chain looking for a {@link DynamoDbException}, since {@code join()} wraps a
     * dependency fault in a {@link CompletionException}.
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
     * Extracts the leaf node of a {@link Path}, so a violation reported as {@code getProfile.playerId}
     * is surfaced to the client as just {@code playerId}, matching the field naming used by the
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
