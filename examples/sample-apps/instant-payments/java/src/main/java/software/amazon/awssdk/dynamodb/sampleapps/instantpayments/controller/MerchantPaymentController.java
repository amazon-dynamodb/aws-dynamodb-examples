package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentsPage;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.MerchantPaymentQueryService;

/**
 * REST controller for merchant payment list APIs.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/v1/merchants/{merchantId}/payments} lists payments for a merchant</li>
 *   <li>{@code GET /api/v1/merchants/{merchantId}/payments/state/{state}} lists by merchant and state</li>
 * </ul>
 *
 * <p>The {@code merchantId} and {@code state} path variables accept only {@value #ID_PATTERN}
 * and up to {@value #ID_MAX_LENGTH} characters.
 * Example values: {@code merchantId=merch_123e4567-e89b-12d3-a456-426614174000},
 * {@code state=COMPLETED}.
 * Class-level {@link Validated} enforces this before values reach DynamoDB GSI keys.
 * Invalid values return HTTP 400 with {@code VALIDATION_ERROR} via
 * {@link GlobalExceptionHandler#handleConstraintViolation(ConstraintViolationException)}.
 * A well-formed unknown state still returns {@code INVALID_PAYMENT_STATE} from the service layer.
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payments")
@Validated
@Tag(name = "Merchant Payments", description = "Query merchant payment projections via Global Secondary Indexes")
public class MerchantPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(MerchantPaymentController.class);

    /** Allowed character set for {@code merchantId} and {@code state}. Length is enforced by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";
    /** Maximum accepted length for the {@code merchantId} and {@code state} path variables. */
    static final int ID_MAX_LENGTH = 64;

    /** Read-model service for GSI-backed merchant payment queries. */
    private final MerchantPaymentQueryService merchantPaymentQueryService;

    /**
     * @param merchantPaymentQueryService read-model service for merchant payment queries
     */
    public MerchantPaymentController(MerchantPaymentQueryService merchantPaymentQueryService) {
        this.merchantPaymentQueryService = merchantPaymentQueryService;
    }

    /**
     * Lists a merchant's payment projections. Default order is newest first.
     *
     * <p>Uses {@code GSI_MERCHANT_PAYMENTS} with a multi-attribute sort key
     * ({@code createdAtUtc + paymentId}).
     *
     * @param merchantId        merchant scope
     * @param limit             optional page size, defaults to 50 when omitted or invalid
     * @param scanIndexForward  optional. When {@code true}, same as DynamoDB Query {@code ScanIndexForward}
     *                          (ascending, oldest first). Omitted or {@code false} yields newest first
     * @param nextToken         optional opaque pagination token from a previous page
     * @return 200 OK with ordered page of projections
     */
    @Operation(
            summary = "List merchant payments",
            description = """
                    Lists a merchant's payments, newest first by default. Supports page size, sort direction, \
                    and opaque pagination tokens. Backed by GSI_MERCHANT_PAYMENTS.""")
    @ApiResponse(responseCode = "200", description = "Payments listed, may be empty",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MerchantPaymentsPage.class)))
    @ApiResponse(responseCode = "400", description = "Invalid merchantId or pagination token",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public CompletableFuture<ResponseEntity<MerchantPaymentsPage>> listMerchantPayments(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String merchantId,
            @Parameter(description = "Maximum results, default 50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "DynamoDB ScanIndexForward. true means oldest first, false or omitted means newest first")
            @RequestParam(required = false) Boolean scanIndexForward,
            @Parameter(description = "Opaque token from a prior GET .../merchants/{merchantId}/payments response only. "
                    + "Do not pass a token from GET .../payments/state/{state}")
            @RequestParam(required = false) String nextToken) {
        logger.debug("List merchant payments: merchantId={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, limit, scanIndexForward, nextToken != null && !nextToken.isBlank());
        return merchantPaymentQueryService.listMerchantPayments(merchantId, limit, scanIndexForward, nextToken)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Lists a merchant's payment projections filtered by lifecycle state. Default order is newest first.
     *
     * <p>Uses {@code GSI_MERCHANT_STATE_PAYMENTS} with a multi-attribute partition key
     * ({@code merchantId + aggregateState}).
     *
     * @param merchantId        merchant scope
     * @param state             payment state (case-insensitive). Invalid values yield 400
     * @param limit             optional page size, defaults to 50 when omitted or invalid
     * @param scanIndexForward  optional. When {@code true}, DynamoDB Query ascending (oldest first).
     *                          Omitted or {@code false} yields newest first
     * @param nextToken         optional opaque pagination token from a previous page
     * @return 200 OK with ordered page of matching projections, or 400 on invalid state
     */
    @Operation(
            summary = "List merchant payments by state",
            description = """
                    Lists a merchant's payments filtered by lifecycle state, newest first by default. State \
                    matching is case insensitive. Backed by GSI_MERCHANT_STATE_PAYMENTS.""")
    @ApiResponse(responseCode = "200", description = "Payments listed, may be empty",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MerchantPaymentsPage.class)))
    @ApiResponse(responseCode = "400", description = "Invalid merchantId, state, or pagination token",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/state/{state}")
    public CompletableFuture<ResponseEntity<MerchantPaymentsPage>> listMerchantPaymentsByState(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String merchantId,
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String state,
            @Parameter(description = "Maximum results, default 50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "DynamoDB ScanIndexForward. true means oldest first, false or omitted means newest first")
            @RequestParam(required = false) Boolean scanIndexForward,
            @Parameter(description = "Opaque token from a prior GET .../merchants/{merchantId}/payments/state/{state} "
                    + "response for the same state value. Do not pass a token from GET .../payments without /state/...")
            @RequestParam(required = false) String nextToken) {
        logger.debug("List merchant payments by state: merchantId={}, state={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, state, limit, scanIndexForward, nextToken != null && !nextToken.isBlank());
        return merchantPaymentQueryService.listMerchantPaymentsByState(merchantId, state, limit,
                        scanIndexForward, nextToken)
                .thenApply(ResponseEntity::ok);
    }
}
