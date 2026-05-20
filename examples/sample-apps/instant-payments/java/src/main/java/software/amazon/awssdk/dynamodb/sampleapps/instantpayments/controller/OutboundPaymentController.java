package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentCreationResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ProcessPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentQueryService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentService;

/**
 * REST controller for outbound payment operations.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/payments/outbound}: create payments with idempotency</li>
 *   <li>{@code GET /api/v1/payments/outbound/{paymentId}}: read aggregate and event history</li>
 *   <li>{@code POST /api/v1/payments/outbound/{paymentId}/process}: optional manual trigger.
 *       after create, processing may also start from DynamoDB Streams on {@code INSERT} of the
 *       {@code OUTBOUND_PAYMENT_CREATED} event. Use for operations or to simulate duplicate downstream
 *       invocations (same idempotency semantics as an at-least-once consumer). See {@link OutboundPaymentProcessor}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/payments/outbound")
@Tag(name = "Outbound Payments", description = "Create and manage outbound payments with idempotency guarantees")
public class OutboundPaymentController {

    private static final Logger logger = LoggerFactory.getLogger(OutboundPaymentController.class);

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
                    Atomically creates PAYMENT_STREAM_HEAD, the first PAYMENT_EVENT, and an IDEMPOTENCY record \
                    using DynamoDB TransactWriteItems. Only the IDEMPOTENCY put is conditional \
                    (attribute_not_exists on that key). The idempotency key prevents duplicate \
                    payments under retries.""")
    @ApiResponse(responseCode = "201", description = "Payment created",
            content = @Content(schema = @Schema(implementation = CreateOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "200", description = "Idempotent retry, returning stored response",
            content = @Content(schema = @Schema(implementation = CreateOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with different payload",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error for missing or invalid fields",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping
    public ResponseEntity<CreateOutboundPaymentResponse> createOutboundPayment(
            @Valid @RequestBody CreateOutboundPaymentRequest request) {
        logger.debug("Received outbound payment request: idempotencyKey={}", request.idempotencyKey());

        PaymentCreationResult result = paymentService.createOutboundPayment(request);

        HttpStatus status = result.newlyCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
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
                    Loads stream head + PAYMENT_EVENT items under the payment partition key \
                    (item collection pattern). Scalars are derived by replaying events. \
                    Each event exposes `eventType` (see PaymentEventType) and optional `reasonCode` for rejections.""")
    @ApiResponse(responseCode = "200", description = "Payment found",
            content = @Content(schema = @Schema(implementation = GetOutboundPaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/{paymentId}")
    public ResponseEntity<GetOutboundPaymentResponse> getOutboundPayment(@PathVariable String paymentId) {
        logger.debug("Get outbound payment: paymentId={}", paymentId);
        GetOutboundPaymentResponse body = paymentQueryService.getOutboundPayment(paymentId);
        return ResponseEntity.ok(body);
    }

    /**
     * Manually triggers processing of an outbound payment.
     *
     * <p>Runs the full validate → reserve → complete/reject lifecycle synchronously.
     * Safe to call multiple times (idempotent). If the payment is already in a
     * terminal state, processing is skipped. Duplicate calls are a deliberate way to
     * exercise the same guarantees as duplicate asynchronous invocations or stream redelivery.
     * See {@link OutboundPaymentProcessor}.
     *
     * @param paymentId the payment to process
     * @return the payment's state after processing
     */
    @Operation(
            summary = "Process outbound payment (manual trigger)",
            description = """
                    Triggers the payment processing lifecycle: validate debtor account, \
                    reserve funds, and complete or reject. This endpoint is idempotent. \
                    Calling it on an already-processed payment returns the current state.""")
    @ApiResponse(responseCode = "200", description = "Payment processed",
            content = @Content(schema = @Schema(implementation = ProcessPaymentResponse.class)))
    @ApiResponse(responseCode = "404", description = "Payment not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/{paymentId}/process")
    public ResponseEntity<ProcessPaymentResponse> processPayment(@PathVariable String paymentId) {
        logger.info("Manual outbound payment processing triggered: paymentId={}", paymentId);

        paymentProcessor.processPayment(paymentId);

        Payment payment = paymentProcessor.getPayment(paymentId);
        return ResponseEntity.ok(paymentMapper.toProcessPaymentResponse(payment));
    }
}
