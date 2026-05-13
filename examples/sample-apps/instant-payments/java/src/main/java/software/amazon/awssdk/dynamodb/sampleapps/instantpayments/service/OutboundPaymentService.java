package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentCreationResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.IdempotencyConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.HashUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.IdempotencyCanonicalizer;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Orchestrates outbound payment creation with idempotency guarantees.
 *
 * <p>Uses {@code TransactWriteItems} to atomically create the stream head, first domain event,
 * and an idempotency record. The idempotency item is the only conditional put
 * ({@code attribute_not_exists(PK)}); duplicate idempotency keys fail the transaction so retries
 * resolve via the stored idempotency row. DynamoDB Streams ({@code INSERT} of
 * {@code OUTBOUND_PAYMENT_CREATED})
 * and {@code POST .../process} both invoke {@link OutboundPaymentProcessor}.
 *
 * <p>{@link CreateOutboundPaymentRequest} includes {@code merchantId} so merchant payment list queries
 * can use GSIs on the stream head.
 */
@Service
public class OutboundPaymentService {

    private static final Logger log = LoggerFactory.getLogger(OutboundPaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    /**
     * @param paymentRepository transactional create and idempotency reads
     * @param paymentMapper     maps requests and snapshots to persistence items
     */
    public OutboundPaymentService(PaymentRepository paymentRepository,
                                  PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    /**
     * Creates an outbound payment with idempotency protection.
     *
     * @param request validated create payload
     * @return new creation result or idempotent match from the idempotency store
     */
    public PaymentCreationResult createOutboundPayment(CreateOutboundPaymentRequest request) {
        String paymentId = generatePaymentId();
        String correlationId = generateCorrelationId();
        Instant createdAtUtc = Instant.now();
        String requestHash = computeRequestHash(request);

        log.info("Creating outbound payment: paymentId={}, correlationId={}, idempotencyKey={}",
                paymentId, correlationId, request.idempotencyKey());

        CreateOutboundPaymentResponse response = new CreateOutboundPaymentResponse(
                paymentId, PaymentState.RECEIVED.name(), correlationId, createdAtUtc);

        PaymentStreamHead streamHead = paymentMapper.toInitialStreamHead(request, paymentId, correlationId, createdAtUtc);
        PaymentEvent createdEvent = paymentMapper.toOutboundPaymentCreatedEvent(
                request, paymentId, correlationId, createdAtUtc);
        IdempotencyRecord idempotency = paymentMapper.toIdempotencyItem(
                request.idempotencyKey(), requestHash, response, createdAtUtc);

        try {
            paymentRepository.createPaymentTransaction(streamHead, createdEvent, idempotency).join();
            log.info("Payment created successfully: paymentId={}", paymentId);
            return new PaymentCreationResult(response, true);
        } catch (CompletionException ex) {
            return handleTransactionFailure(ex, request.idempotencyKey(), requestHash);
        }
    }

    /**
     * Interprets a failed create transact: idempotency conditional failure is handled as retry; other causes propagate.
     *
     * @param ex              wrapper from {@code join()} on the transact future
     * @param idempotencyKey  client key from the request
     * @param requestHash     canonical-request digest used to compare with the stored record
     * @return cached response when the failure was idempotency-only and the stored hash matches
     * @throws RuntimeException when the cause is not an idempotency conditional conflict
     */
    private PaymentCreationResult handleTransactionFailure(CompletionException ex,
                                                           String idempotencyKey,
                                                           String requestHash) {
        Throwable cause = ex.getCause();

        if (cause instanceof TransactionCanceledException tce && isIdempotencyConflict(tce)) {
            log.info("Idempotency key already exists, checking for retry: key={}", idempotencyKey);
            return handleIdempotencyRetry(idempotencyKey, requestHash);
        }

        throw new RuntimeException("Failed to create payment transaction", cause);
    }

    /**
     * Loads the committed idempotency row after a conditional conflict.
     *
     * @param idempotencyKey client key
     * @param requestHash    digest of the current request body
     * @return stored {@link CreateOutboundPaymentResponse} when the hash matches this request
     * @throws IdempotencyConflictException when the key exists but the payload hash differs
     * @throws IllegalStateException when the row is missing despite a reported conditional failure
     */
    private PaymentCreationResult handleIdempotencyRetry(String idempotencyKey, String requestHash) {
        IdempotencyRecord existing = paymentRepository.getIdempotencyRecord(idempotencyKey).join();

        if (existing == null) {
            throw new IllegalStateException(
                    "Idempotency record not found after transaction conflict: " + idempotencyKey);
        }

        if (requestHash.equals(existing.getRequestHash())) {
            log.info("Idempotent retry detected, returning stored response: key={}, paymentId={}",
                    idempotencyKey, existing.getResponseSnapshot().paymentId());
            return new PaymentCreationResult(existing.getResponseSnapshot(), false);
        }

        throw new IdempotencyConflictException(idempotencyKey);
    }

    /**
     * Returns {@code true} when the create transact failed only because the idempotency key already exists.
     *
     * @implNote {@link #createOutboundPayment} builds {@code TransactWriteItems} in a fixed order:
     *     stream head put, first event put, idempotency conditional put. DynamoDB reports per-item
     *     cancellation reasons in that same order; index {@code 2} is therefore the idempotency row's
     *     {@code attribute_not_exists(PK)} check. Other conditional failures would surface at indices
     *     {@code 0} or {@code 1} and are not treated as idempotent replay.
     */
    private boolean isIdempotencyConflict(TransactionCanceledException tce) {
        var reasons = tce.cancellationReasons();
        if (reasons == null || reasons.size() < 3) {
            return false;
        }
        return "ConditionalCheckFailed".equals(reasons.get(2).code());
    }

    /**
     * SHA-256 hex digest of {@link IdempotencyCanonicalizer#canonicalForm(CreateOutboundPaymentRequest)}.
     */
    private String computeRequestHash(CreateOutboundPaymentRequest request) {
        return HashUtils.sha256(IdempotencyCanonicalizer.canonicalForm(request));
    }

    /**
     * @return new logical id with {@code pay_} prefix
     */
    private String generatePaymentId() {
        return "pay_" + UUID.randomUUID();
    }

    /**
     * @return new correlation id with {@code corr_} prefix
     */
    private String generateCorrelationId() {
        return "corr_" + UUID.randomUUID();
    }
}
