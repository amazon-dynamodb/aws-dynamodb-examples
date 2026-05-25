package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PurchaseService;

/**
 * REST endpoint for in-game purchases.
 *
 * <p>Executes an atomic cross-table transaction that deducts currency and records
 * a purchase event. A client-supplied idempotency key prevents double-spend on retries.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/players/{playerId}/purchases}: atomic purchase with idempotency</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Purchases", description = "Atomic in-game purchases with idempotency")
public class PurchaseController {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseController.class);

    /** Cross-table purchase transaction orchestration. */
    private final PurchaseService purchaseService;

    /**
     * Constructs the controller.
     *
     * @param purchaseService purchase transaction business logic
     */
    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    /**
     * Executes an in-game purchase for the given player.
     *
     * @param playerId the purchasing player
     * @param request  item id, cost, and idempotency key
     * @return the purchase outcome with updated profile
     */
    @Operation(
            summary = "Execute in-game purchase",
            description = """
                    Buys an in-game item by debiting soft currency from the wallet. Atomically \
                    debits the WALLET item and writes a PURCHASE GameEvent with TransactWriteItems. \
                    Returns a full snapshot with playerId, profile, wallet, settings, status, and \
                    purchaseEventId. The same clientRequestId returns status IDEMPOTENT_REPLAY \
                    without charging twice. Rejected when balance is insufficient.""")
    @ApiResponse(responseCode = "200", description = "Purchase completed or idempotent replay with full snapshot",
            content = @Content(schema = @Schema(implementation = PurchaseResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (VALIDATION_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player or wallet not found (PLAYER_NOT_FOUND or WALLET_NOT_FOUND)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Insufficient funds or wallet version conflict (INSUFFICIENT_FUNDS or STALE_VERSION)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/players/{playerId}/purchases")
    public ResponseEntity<PurchaseResponse> purchase(
            @Parameter(description = "Internal player id")
            @PathVariable String playerId,
            @Valid @RequestBody PurchaseRequest request) {
        logger.debug("Received purchase request [playerId={}, itemId={}, softCurrencyCost={}, clientRequestId={}]",
                playerId, request.itemId(), request.softCurrencyCost(), request.clientRequestId());
        PurchaseResponse response = purchaseService.executePurchase(playerId, request);
        return ResponseEntity.ok(response);
    }
}
