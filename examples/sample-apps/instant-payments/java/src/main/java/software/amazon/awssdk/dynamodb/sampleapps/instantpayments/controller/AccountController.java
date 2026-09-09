package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetAccountResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.AccountQueryService;

/**
 * REST controller for account read APIs.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/v1/accounts/{accountId}} returns balances and reservations</li>
 *   <li>{@code POST /api/v1/accounts/{accountId}/batch-get-reservations} loads selected reservations</li>
 * </ul>
 *
 * <p>The {@code accountId} path variable accepts only {@value #ID_PATTERN} and up to
 * {@value #ID_MAX_LENGTH} characters. Example valid value: {@code acc_usd_1}.
 * Class-level {@link Validated} enforces this before the value reaches DynamoDB.
 * Invalid values return HTTP 400 with {@code VALIDATION_ERROR} via
 * {@link GlobalExceptionHandler#handleConstraintViolation(ConstraintViolationException)}.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Validated
@Tag(name = "Accounts", description = "Query account balances and reservations")
public class AccountController {

    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    /** Allowed character set for {@code accountId}. Length is enforced by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";
    /** Maximum accepted length for the {@code accountId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Read-model service for account and reservation queries. */
    private final AccountQueryService accountQueryService;

    /**
     * @param accountQueryService read-model service for account queries
     */
    public AccountController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    /**
     * Returns the account with current/available balances and all associated reservations
     * (item collection pattern: single Query on {@code PK=ACCOUNT#{accountId}}).
     *
     * @param accountId identifier to load
     * @return HTTP 200 with account and reservations
     */
    @Operation(
            summary = "Get account with reservations",
            description = """
                    Returns current and available balances plus all open fund reservations for the account. \
                    Reservations are loaded with the account in one partition query (item collection pattern).""")
    @ApiResponse(responseCode = "200", description = "Account found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GetAccountResponse.class)))
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid accountId",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{accountId}")
    public CompletableFuture<ResponseEntity<GetAccountResponse>> getAccount(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String accountId) {
        logger.debug("Get account: accountId={}", accountId);
        return accountQueryService.getAccount(accountId)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Batch-gets specific reservations for one account using DynamoDB {@code BatchGetItem}.
     *
     * <p>Each reservation is loaded by its composite key where {@code PK} is {@code ACCOUNT#} plus
     * the path {@code accountId} and {@code SK} is {@code RESERVATION#} plus each reservation id.
     * Identifiers without a row appear in {@code missingReservationIds}. HTTP status stays
     * {@code 200} so callers merge partial success with the request list.
     *
     * @param accountId account that owns the reservations
     * @param request   JSON body listing {@code reservationIds} (at most 100)
     * @return found reservations plus missing ids
     */
    @Operation(
            summary = "Batch get reservations for an account",
            description = """
                    Loads the requested reservations for one account. Missing identifiers are listed separately \
                    so callers can reconcile partial results. Duplicate ids in the body are deduplicated before \
                    lookup. Implemented with DynamoDB BatchGetItem.""")
    @ApiResponse(responseCode = "200", description = "Partial or full success",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = BatchGetReservationsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid accountId or request body",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/{accountId}/batch-get-reservations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<ResponseEntity<BatchGetReservationsResponse>> batchGetReservations(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String accountId,
            @Valid @RequestBody BatchGetReservationsRequest request) {
        logger.debug("Batch get reservations: accountId={}, count={}", accountId, request.reservationIds().size());
        return accountQueryService.batchGetReservations(accountId, request)
                .thenApply(ResponseEntity::ok);
    }
}
