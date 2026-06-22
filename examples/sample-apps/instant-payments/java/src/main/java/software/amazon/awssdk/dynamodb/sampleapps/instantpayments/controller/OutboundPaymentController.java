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

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ProcessPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentQueryService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentService;

/**
 * REST controller for outbound payment APIs.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /api/v1/payments/outbound} creates a payment with idempotency</li>
 *   <li>{@code GET /api/v1/payments/outbound/{paymentId}} returns aggregate and event history</li>
 *   <li>{@code POST /api/v1/payments/outbound/{paymentId}/process} triggers processing manually</li>
 * </ul>
 *
 * <p>The {@code paymentId} path variable accepts only {@value #ID_PATTERN} and up to
 * {@value #ID_MAX_LENGTH} characters. Example valid value:
 * {@code pay_123e4567-e89b-12d3-a456-426614174000}.
 * Class-level {@link Validated} enforces this before the value reaches DynamoDB.
 * Invalid values return HTTP 400 with {@code VALIDATION_ERROR} via
 * {@link GlobalExceptionHandler#handleConstraintViolation(ConstraintViolationException)}.
 */
@RestController
@RequestMapping("/api/v1/payments/outbound")
@Validated
@Tag(name = "Outbound Payments", description = "Create and manage outbound payments with idempotency guarantees")
public class OutboundPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(OutboundPaymentController.class);

    /** Allowed character set for {@code paymentId}. Length is enforced by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";
    /** Maximum accepted length for the {@code paymentId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Idempotent create flow for outbound payments. */
    private final OutboundPaymentService paymentService;
    /** Read model for GET outbound payment. */
    private final OutboundPaymentQueryService paymentQueryService;
    /** Validate, reserve, and complete lifecycle processor. */
    private final OutboundPaymentProcessor paymentProcessor;
    /** Maps domain models to API DTOs. */
    private final PaymentMapper paymentMapper;

    /**
     * @param paymentService      idempotent create flow
     * @param paymentQueryService read model for GET
     * @param paymentProcessor    validate / reserve / complete lifecycle
     * @param paymentMapper       model-to-DTO mapping
     */
    public OutboundPaymentController(OutboundPaymentService paymentService,
                                     OutboundPaymentQueryService paymentQueryService,
                                     OutboundPaymentProcessor paymentProcessor,
                                     PaymentMapper paymentMapper) {
        this.paymentService = paymentService;
        this.paymentQueryService = paymentQueryService;
        this.paymentProcessor = paymentProcessor;
        this.paymentMapper = paymentMapper;
    }

    /**
     * Creates an outbound payment with idempotency protection.
     *
     * <p>If the {@code idempotencyKey} has been seen before with the same payload,
     * returns the stored response with HTTP 200. If the payload differs, returns
     * HTTP 409 Conflict.
     *
     * @param request the payment creation request
     * @return HTTP 201 with the new payment, or HTTP 200 with the cached response
     */
    @Operation(
            summary = "Create outbound payment",
            description = """
                    Starts an outbound payment under the supplied idempotency key. Retries with the same key \
                    and payload return the stored outcome instead of creating a duplicate. One atomic \
                    DynamoDB TransactWriteItems writes the payment stream, first event, and idempotency \
                    record together.""")
    @ApiResponse(responseCode = "201", description = "Payment created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "200", description = "Idempotent retry with stored outcome",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with a different payload",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request body",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public CompletableFuture<ResponseEntity<CreateOutboundPaymentResponse>> createOutboundPayment(
            @Valid @RequestBody CreateOutboundPaymentRequest request) {
        logger.debug("Received outbound payment request: idempotencyKey={}", request.idempotencyKey());

        return paymentService.createOutboundPayment(request)
                .thenApply(result -> {
                    HttpStatus status = result.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK;
                    return ResponseEntity.status(status).body(result.response());
                });
    }

    /**
     * Returns the payment aggregate and ordered PAYMENT_EVENT history (single-table {@code Query}
     * on the payment partition).
     *
     * @param paymentId identifier to load
     * @return HTTP 200 with full read model and events
     */
    @Operation(
            summary = "Get outbound payment",
            description = """
                    Returns the payment's current state and full event history for audit and troubleshooting. \
                    Balances and status are folded from the ordered event stream (item collection query on \
                    one partition).""")
    @ApiResponse(responseCode = "200", description = "Payment found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GetOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid paymentId",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{paymentId}")
    public CompletableFuture<ResponseEntity<GetOutboundPaymentResponse>> getOutboundPayment(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String paymentId) {
        logger.debug("Get outbound payment: paymentId={}", paymentId);
        return paymentQueryService.getOutboundPayment(paymentId)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * Manually triggers processing of an outbound payment.
     *
     * <p>Runs the full validate, reserve, and complete or reject lifecycle asynchronously.
     * Safe to call multiple times (idempotent). If the payment is already in a
     * terminal state, processing is skipped. Duplicate calls are a deliberate way to
     * exercise the same guarantees as duplicate asynchronous invocations or stream redelivery.
     * See {@link OutboundPaymentProcessor}.
     *
     * <p>After processing, the handler reloads the payment partition via
     * {@link OutboundPaymentProcessor#getPayment(String)} so the response reflects post-transact state.
     * That is a deliberate second DynamoDB read, not a bug: {@code processPayment} returns
     * {@code CompletableFuture<Void>} and does not carry the folded aggregate. The extra read existed
     * before the async refactor (sync controller called process, then get). A future optimization could
     * return the folded payment from the processor in one chain.
     *
     * @param paymentId the payment to process
     * @return the payment's state after processing
     */
    @Operation(
            summary = "Process outbound payment (manual trigger)",
            description = """
                    Runs validation, fund reservation, and settlement or rejection for the payment. Safe to \
                    call again: an already finished payment returns its current state without duplicate side \
                    effects.""")
    @ApiResponse(responseCode = "200", description = "Payment processed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ProcessPaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid paymentId",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{paymentId}/process")
    public CompletableFuture<ResponseEntity<ProcessPaymentResponse>> processPayment(
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String paymentId) {
        logger.debug("Manual outbound payment processing triggered: paymentId={}", paymentId);

        return paymentProcessor.processPayment(paymentId)
                .thenCompose(ignored -> paymentProcessor.getPayment(paymentId))
                .thenApply(payment -> ResponseEntity.ok(paymentMapper.toProcessPaymentResponse(payment)));
    }
}
