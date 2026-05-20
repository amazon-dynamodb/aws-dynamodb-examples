package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentsPage;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.MerchantPaymentQueryService;

/**
 * REST controller for merchant-scoped payment list queries (GSI-backed read models).
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/v1/merchants/{merchantId}/payments}: list payments by merchant (GSI with
 *       multi-attribute sort key)</li>
 *   <li>{@code GET /api/v1/merchants/{merchantId}/payments/state/{state}}: list payments by
 *       merchant and state (GSI with multi-attribute partition key)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payments")
@Tag(name = "Merchant Payments", description = "Query merchant payment projections via Global Secondary Indexes")
public class MerchantPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(MerchantPaymentController.class);

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
                    Returns payment projections for the requested merchant. Default order is newest \
                    first (DynamoDB ScanIndexForward=false). Pass scanIndexForward=true for oldest first. \
                    Uses GSI_MERCHANT_PAYMENTS (multi-attribute sort key: createdAtUtc + paymentId). \
                    Default page size is 50 when limit is omitted or invalid. Pass nextToken from a \
                    previous response on this route to continue pagination.""")
    @ApiResponse(responseCode = "200", description = "Payments listed (may be empty)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MerchantPaymentsPage.class)))
    @ApiResponse(responseCode = "400",
            description = """
                    HTTP 400 with ErrorResponse.error INVALID_PAGINATION_TOKEN for a malformed or \
                    undecodable nextToken, or for a token from GET .../payments/state/{state} used on \
                    this route.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500",
            description = "Server error with ErrorResponse.error INTERNAL_ERROR",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping
    public ResponseEntity<MerchantPaymentsPage> listMerchantPayments(
            @PathVariable String merchantId,
            @Parameter(description = "Maximum results, default 50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "DynamoDB ScanIndexForward. true means oldest first, false or omitted means newest first")
            @RequestParam(required = false) Boolean scanIndexForward,
            @Parameter(description = "Opaque token from a prior GET .../merchants/{merchantId}/payments response only. "
                    + "Do not pass a token from GET .../payments/state/{state}")
            @RequestParam(required = false) String nextToken) {
        logger.debug("List merchant payments: merchantId={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, limit, scanIndexForward, nextToken != null && !nextToken.isBlank());
        MerchantPaymentsPage page =
                merchantPaymentQueryService.listMerchantPayments(merchantId, limit, scanIndexForward, nextToken);
        return ResponseEntity.ok(page);
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
                    Returns payment projections for the requested merchant filtered by state. \
                    Default order is newest first (DynamoDB ScanIndexForward=false). Pass \
                    scanIndexForward=true for oldest first. Uses GSI_MERCHANT_STATE_PAYMENTS \
                    (multi-attribute partition key: merchantId + aggregateState). State matching is \
                    case-insensitive. Default page size is 50 when limit is omitted or invalid. \
                    Pass nextToken from a previous response on this route to continue pagination.""")
    @ApiResponse(responseCode = "200", description = "Payments listed (may be empty)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = MerchantPaymentsPage.class)))
    @ApiResponse(responseCode = "400",
            description = """
                    HTTP 400 with ErrorResponse.error INVALID_PAYMENT_STATE for an unknown state, \
                    ErrorResponse.error INVALID_PAGINATION_TOKEN for a malformed or undecodable nextToken, \
                    or ErrorResponse.error INVALID_PAGINATION_TOKEN when a token from \
                    GET .../merchants/{merchantId}/payments without /state/... is used on this route.""",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500",
            description = "Server error with ErrorResponse.error INTERNAL_ERROR",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/state/{state}")
    public ResponseEntity<MerchantPaymentsPage> listMerchantPaymentsByState(
            @PathVariable String merchantId,
            @PathVariable String state,
            @Parameter(description = "Maximum results, default 50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "DynamoDB ScanIndexForward. true means oldest first, false or omitted means newest first")
            @RequestParam(required = false) Boolean scanIndexForward,
            @Parameter(description = "Opaque token from a prior GET .../merchants/{merchantId}/payments/state/{state} "
                    + "response for the same state value. Do not pass a token from GET .../payments without /state/...")
            @RequestParam(required = false) String nextToken) {
        logger.debug("List merchant payments by state: merchantId={}, state={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, state, limit, scanIndexForward, nextToken != null && !nextToken.isBlank());
        MerchantPaymentsPage page =
                merchantPaymentQueryService.listMerchantPaymentsByState(merchantId, state, limit,
                        scanIndexForward, nextToken);
        return ResponseEntity.ok(page);
    }
}
