package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;

/**
 * Translates domain exceptions into consistent HTTP error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * Maps {@link PlayerNotFoundException} to HTTP 404.
     *
     * @param ex contains the missing player id
     * @return JSON error envelope
     */
    @ExceptionHandler(PlayerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePlayerNotFound(PlayerNotFoundException ex) {
        logger.warn("Player profile not found [errorCode=PLAYER_NOT_FOUND, detail={}]",
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
        logger.warn("Player wallet not found [errorCode=WALLET_NOT_FOUND, detail={}]",
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
     * Fallback handler for unexpected exceptions. Maps to HTTP 500 without leaking internals.
     *
     * @param ex the unexpected error
     * @return JSON error envelope with a generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        logger.error("Unhandled exception during request processing [errorCode=INTERNAL_ERROR, detail={}]",
                ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", Instant.now()));
    }
}
