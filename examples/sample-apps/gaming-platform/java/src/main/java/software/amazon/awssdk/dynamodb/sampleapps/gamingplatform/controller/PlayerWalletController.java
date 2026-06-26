package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

import java.util.concurrent.CompletableFuture;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetWalletResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.CurrencyRewardService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerWalletService;

/**
 * REST controller for the player wallet (currency balance).
 *
 * <p>The wallet is stored as a separate {@code SK = WALLET} item under the same
 * {@code USER#<playerId>} partition as the player profile. Isolating it means currency
 * writes from purchases never conflict on the profile item's optimistic lock version.
 *
 * <p>Wallet debits occur only through {@code POST /api/v1/players/{playerId}/purchases}.
 * Wallet credits (gameplay rewards, daily login, admin grants) are exposed via
 * {@code POST /api/v1/players/{playerId}/wallet/earn}. Both operations use an idempotency
 * key so retries are safe.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/v1/players/{playerId}/wallet}: read wallet balance</li>
 *   <li>{@code POST /api/v1/players/{playerId}/wallet/earn}: credit soft currency</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Player Wallet", description = "Read wallet balance and credit soft currency")
public class PlayerWalletController {

    private static final Logger logger = LoggerFactory.getLogger(PlayerWalletController.class);

    /** Allowed character set for the {@code playerId} path variable. Length is bounded by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    /** Maximum accepted length for the {@code playerId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Wallet read operations. */
    private final PlayerWalletService playerWalletService;

    /** Wallet credit (earn) operations. */
    private final CurrencyRewardService currencyRewardService;

    /**
     * Constructs the controller.
     *
     * @param playerWalletService wallet business logic
     * @param currencyRewardService currency earn orchestration
     */
    public PlayerWalletController(PlayerWalletService playerWalletService,
                                   CurrencyRewardService currencyRewardService) {
        this.playerWalletService = playerWalletService;
        this.currencyRewardService = currencyRewardService;
    }

    /**
     * Returns the current soft currency balance and wallet version for a player.
     *
     * <p>The {@code version} field in the response reflects the wallet's own optimistic lock
     * counter. It advances exclusively through purchase transactions and allows callers to
     * detect stale reads without fetching the full profile.
     *
     * @param playerId the target player
     * @return 200 OK with the wallet, or 404 if the player does not exist
     */
    @Operation(
            summary = "Get player wallet",
            description = """
                    Returns a player's soft currency balance and wallet version. Loads the WALLET \
                    item on a separate sort key from the profile so currency reads do not contend \
                    with progression writes. Response is a slice with only the wallet object. \
                    Debits happen only through the purchases endpoint.""")
    @ApiResponse(responseCode = "200", description = "Wallet slice found",
            content = @Content(schema = @Schema(implementation = GetWalletResponse.class)))
    @ApiResponse(responseCode = "404", description = "Wallet not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/players/{playerId}/wallet")
    public CompletableFuture<ResponseEntity<GetWalletResponse>> getWallet(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId) {
        logger.debug("Received get player wallet request [playerId={}]", playerId);
        return playerWalletService.getWallet(playerId).thenApply(ResponseEntity::ok);
    }

    /**
     * Credits soft currency to a player wallet (gameplay reward, daily login, or admin grant).
     *
     * <p>The operation is idempotent: supplying the same {@code clientRequestId} returns
     * {@code IDEMPOTENT_REPLAY} with the current wallet state without crediting the player twice.
     * The credit is atomic: a {@code CURRENCY_GRANT} GameEvent is written in the same DynamoDB
     * transaction so the economy audit trail is always consistent with the balance.
     *
     * <p><strong>Intended callers:</strong> game server (match rewards), scheduler (daily login),
     * admin or CS tooling (manual grants). This endpoint should be protected at the network or
     * auth layer so end-users cannot self-award currency.
     *
     * @param playerId the player to credit
     * @param request  amount, earn reason, and idempotency key
     * @return 200 OK with the updated balance, status, and event id (404 if the player does not exist)
     */
    @Operation(
            summary = "Earn soft currency",
            description = """
                    Credits soft currency for match wins, daily login, level-up bonuses, or admin \
                    grants. Intended for trusted backends such as game servers, schedulers, or admin \
                    tooling. Atomically updates the wallet and writes a CURRENCY_GRANT GameEvent with \
                    TransactWriteItems. Returns a wallet-focused response with playerId, wallet \
                    (balance and version), status, and earnEventId. The same clientRequestId returns \
                    status IDEMPOTENT_REPLAY without double crediting. Valid reason values are \
                    MATCH_WIN, LEVEL_UP_BONUS, DAILY_LOGIN, and ADMIN_GRANT. Protect this endpoint at \
                    the network or auth layer.""")
    @ApiResponse(responseCode = "200", description = "Credit applied or idempotent replay (wallet slice)",
            content = @Content(schema = @Schema(implementation = WalletEarnResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Wallet not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/players/{playerId}/wallet/earn")
    public CompletableFuture<ResponseEntity<WalletEarnResponse>> earnCurrency(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId,
            @Valid @RequestBody WalletEarnRequest request) {
        logger.debug("Received earn currency request [playerId={}, amount={}, reason={}, clientRequestId={}]",
                playerId, request.amount(), request.reason(), request.clientRequestId());
        return currencyRewardService.grantCurrency(playerId, request).thenApply(ResponseEntity::ok);
    }
}
