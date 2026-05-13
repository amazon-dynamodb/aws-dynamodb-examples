package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.AccountQueryService;

/**
 * REST controller for account balance and reservation queries.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/v1/accounts/{accountId}} (read account balances and reservations)</li>
 *   <li>{@code POST /api/v1/accounts/{accountId}/batch-get-reservations} (batch load named reservations)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Query account balances and reservations")
public class AccountController {

    /** Structured log for this controller. */
    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    /** Account read facade used by both endpoints. */
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
                    Loads the ACCOUNT row and all RESERVATION items under the same partition key \
                    (item collection pattern). Reservations are sorted by sort key. Available \
                    balance reflects active reservations. Current balance reflects posted debits.""")
    @ApiResponse(responseCode = "200", description = "Account found",
            content = @Content(schema = @Schema(implementation = GetAccountResponse.class)))
    @ApiResponse(responseCode = "404", description = "Account not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{accountId}")
    public ResponseEntity<GetAccountResponse> getAccount(@PathVariable String accountId) {
        log.debug("Get account: accountId={}", accountId);
        GetAccountResponse body = accountQueryService.getAccount(accountId);
        return ResponseEntity.ok(body);
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
                    Loads RESERVATION rows with BatchGetItem for the path account. Duplicate ids in \
                    the JSON body are allowed but deduplicated before the repository call, so \
                    repeated values never produce duplicate response rows. Any id without a row \
                    appears in missingReservationIds. Found reservations follow the first-seen \
                    order from the request after deduplication.""")
    @ApiResponse(responseCode = "200", description = "Partial or full success",
            content = @Content(schema = @Schema(implementation = BatchGetReservationsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (empty list, blank id, or too many ids)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/{accountId}/batch-get-reservations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BatchGetReservationsResponse> batchGetReservations(
            @PathVariable String accountId,
            @Valid @RequestBody BatchGetReservationsRequest request) {
        log.debug("Batch get reservations: accountId={}, count={}", accountId, request.reservationIds().size());
        BatchGetReservationsResponse responseBody = accountQueryService.batchGetReservations(accountId, request);
        return ResponseEntity.ok(responseBody);
    }
}
