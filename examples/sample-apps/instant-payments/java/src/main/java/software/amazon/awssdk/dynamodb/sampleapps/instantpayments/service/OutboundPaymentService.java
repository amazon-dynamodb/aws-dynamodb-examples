package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentCreationResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.IdempotencyConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.CreatePaymentTransactItemOrder;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.HashUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.IdempotencyCanonicalizer;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Orchestrates outbound payment creation with idempotency guarantees.
 *
 * <p>Uses {@code TransactWriteItems} to atomically create the stream head, first domain event,
 * and an idempotency record. The idempotency item is the only conditional put
 * ({@code attribute_not_exists(PK)}). Duplicate idempotency keys fail the transaction so retries
 * resolve via the stored idempotency row. DynamoDB Streams ({@code INSERT} of
 * {@code OUTBOUND_PAYMENT_CREATED})
 * and {@code POST .../process} both invoke {@link OutboundPaymentProcessor}.
 *
 * <p>Returns {@link CompletableFuture} so async MVC controllers can compose without blocking Tomcat
 * worker threads.
 *
 * <p>{@link CreateOutboundPaymentRequest} includes {@code merchantId} so merchant payment list queries
 * can use GSIs on the stream head.
 */
@Service
public class OutboundPaymentService {

    private static final Logger logger = LoggerFactory.getLogger(OutboundPaymentService.class);

    /** Persistence for transactional create and idempotency reads. */
    private final PaymentRepository paymentRepository;
    /** Maps requests and snapshots to persistence items. */
    private final PaymentMapper paymentMapper;
    /** Validated idempotency TTL in seconds, sourced from {@link DynamoDbConfig}. */
    private final long idempotencyTtlSeconds;

    /**
     * @param paymentRepository transactional create and idempotency reads
     * @param paymentMapper     maps requests and snapshots to persistence items
     * @param dynamoDbConfig    provides the validated idempotency TTL
     */
    public OutboundPaymentService(PaymentRepository paymentRepository,
                                  PaymentMapper paymentMapper,
                                  DynamoDbConfig dynamoDbConfig) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.idempotencyTtlSeconds = dynamoDbConfig.getIdempotencyTtlSeconds();
    }

    /**
     * Creates an outbound payment with idempotency protection.
     *
     * @param request validated create payload
     * @return new creation result or idempotent match from the idempotency store
     */
    public CompletableFuture<PaymentCreationResult> createOutboundPayment(CreateOutboundPaymentRequest request) {
        String paymentId = generatePaymentId();
        String correlationId = generateCorrelationId();
        Instant createdAtUtc = Instant.now();
        String requestHash = computeRequestHash(request);

        logger.debug("Creating outbound payment: paymentId={}, correlationId={}, idempotencyKey={}",
                paymentId, correlationId, request.idempotencyKey());

        CreateOutboundPaymentResponse response = new CreateOutboundPaymentResponse(
                paymentId, PaymentState.RECEIVED.name(), correlationId, createdAtUtc);

        long expiresAtEpochSecond = computeIdempotencyExpiry(createdAtUtc);

        PaymentStreamHead streamHead = paymentMapper.toInitialStreamHead(request, paymentId, correlationId, createdAtUtc);
        PaymentEvent createdEvent = paymentMapper.toOutboundPaymentCreatedEvent(
                request, paymentId, correlationId, createdAtUtc);
        IdempotencyRecord idempotency = paymentMapper.toIdempotencyItem(
                request.idempotencyKey(), requestHash, response, createdAtUtc, expiresAtEpochSecond);

        return paymentRepository.createPaymentTransaction(streamHead, createdEvent, idempotency)
                .thenApply(ignored -> {
                    logger.debug("Payment created successfully: paymentId={}", paymentId);
                    return new PaymentCreationResult(response, true);
                })
                .handle((result, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    Throwable failure = error instanceof CompletionException ? error : new CompletionException(error);
                    return handleTransactionFailure((CompletionException) failure,
                            request.idempotencyKey(), requestHash);
                })
                .thenCompose(future -> future);
    }

    /**
     * Interprets a failed create transact. Idempotency conditional failure is handled as retry, other causes propagate.
     *
     * @param ex              wrapper from an exceptional transact completion
     * @param idempotencyKey  client key from the request
     * @param requestHash     canonical-request digest used to compare with the stored record
     * @return cached response when the failure was idempotency-only and the stored hash matches
     */
    private CompletableFuture<PaymentCreationResult> handleTransactionFailure(CompletionException ex,
                                                                              String idempotencyKey,
                                                                              String requestHash) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;

        if (cause instanceof TransactionCanceledException tce && isIdempotencyConflict(tce)) {
            logger.debug("Idempotency key already exists, checking for retry: idempotencyKey={}", idempotencyKey);
            return handleIdempotencyRetry(idempotencyKey, requestHash);
        }

        return CompletableFuture.failedFuture(new RuntimeException("Failed to create payment transaction", cause));
    }

    /**
     * Loads the committed idempotency row after a conditional conflict.
     *
     * @param idempotencyKey client key
     * @param requestHash    digest of the current request body
     * @return stored {@link CreateOutboundPaymentResponse} when the hash matches this request
     */
    private CompletableFuture<PaymentCreationResult> handleIdempotencyRetry(String idempotencyKey,
                                                                            String requestHash) {
        return paymentRepository.getIdempotencyRecord(idempotencyKey)
                .thenApply(existing -> {
                    if (existing == null) {
                        throw new IllegalStateException(
                                "Idempotency record not found after transaction conflict: " + idempotencyKey);
                    }

                    if (requestHash.equals(existing.getRequestHash())) {
                        logger.debug("Idempotent retry detected, returning stored response: idempotencyKey={}, paymentId={}",
                                idempotencyKey, existing.getResponseSnapshot().paymentId());
                        return new PaymentCreationResult(existing.getResponseSnapshot(), false);
                    }

                    throw new IdempotencyConflictException(idempotencyKey);
                });
    }

    /**
     * Returns {@code true} when the create transact failed only because the idempotency key already exists.
     *
     * @implNote {@link #createOutboundPayment} builds {@code TransactWriteItems} in the fixed order named by
     *     {@link CreatePaymentTransactItemOrder}: stream head put, first event put, idempotency conditional put. DynamoDB
     *     reports per-item cancellation reasons in that same order, so
     *     {@link CreatePaymentTransactItemOrder#IDEMPOTENCY} gives the index of the idempotency row's
     *     {@code attribute_not_exists(PK)} check rather than a hard-coded literal. Other conditional failures
     *     surface at {@link CreatePaymentTransactItemOrder#STREAM_HEAD} or {@link CreatePaymentTransactItemOrder#FIRST_EVENT} and are
     *     not treated as idempotent replay.
     */
    private boolean isIdempotencyConflict(TransactionCanceledException tce) {
        var reasons = tce.cancellationReasons();
        int idempotencyIndex = CreatePaymentTransactItemOrder.IDEMPOTENCY.index();
        if (reasons == null || reasons.size() <= idempotencyIndex) {
            return false;
        }
        return "ConditionalCheckFailed".equals(reasons.get(idempotencyIndex).code());
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

    /**
     * Computes the DynamoDB TTL epoch second for an idempotency record and validates that
     * the resulting expiry is in the future.
     *
     * @param createdAtUtc creation instant of the payment
     * @return epoch second at which the idempotency record should expire
     * @throws IllegalStateException if the computed expiry is not after the current time
     */
    private long computeIdempotencyExpiry(Instant createdAtUtc) {
        long expiresAtEpochSecond = createdAtUtc.getEpochSecond() + idempotencyTtlSeconds;
        long nowEpoch = Instant.now().getEpochSecond();
        if (expiresAtEpochSecond <= nowEpoch) {
            throw new IllegalStateException(
                    "Idempotency expiresAtEpochSecond must be after now: expiresAtEpochSecond="
                            + expiresAtEpochSecond
                            + ", nowEpochSecond="
                            + nowEpoch);
        }
        return expiresAtEpochSecond;
    }
}
