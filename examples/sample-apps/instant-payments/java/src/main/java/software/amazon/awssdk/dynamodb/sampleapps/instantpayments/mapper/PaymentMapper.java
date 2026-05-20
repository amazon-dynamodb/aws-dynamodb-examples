package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper;

import java.time.Instant;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetAccountResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ProcessPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ReservationResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;

/**
 * Centralizes all model-to-DTO and DTO-to-model mapping for the instant-payments module.
 *
 * <p>Covers write-path items (stream initialization + first event), account and payment read models,
 * and {@link MerchantPaymentProjection} for merchant GSI list endpoints.
 *
 * <p>Spring injects {@code dynamodb.idempotency-ttl-seconds} into {@code idempotencyTtlSeconds} (default {@code 2592000}
 * seconds in the property placeholder). {@link #validateIdempotencyTtlConfiguration()} enforces the inclusive range
 * {@link #MIN_IDEMPOTENCY_TTL_SECONDS}-{@link #MAX_IDEMPOTENCY_TTL_SECONDS}.
 */
@Component
public class PaymentMapper {

    /**
     * Minimum allowed idempotency TTL configuration: one hour (seconds).
     */
    private static final long MIN_IDEMPOTENCY_TTL_SECONDS = 3_600L;

    /**
     * Maximum allowed idempotency TTL configuration: {@code 31_536_000} seconds (365 × 24 × 3,600).
     */
    private static final long MAX_IDEMPOTENCY_TTL_SECONDS = 31_536_000L;

    /** TTL in seconds for idempotency items, from {@code dynamodb.idempotency-ttl-seconds}. */
    @Value("${dynamodb.idempotency-ttl-seconds:2592000}")
    private final long idempotencyTtlSeconds = 2_592_000L;

    /**
     * Ensures {@code dynamodb.idempotency-ttl-seconds} is within the allowed range after injection.
     *
     * @throws IllegalStateException if the configured TTL is outside bounds
     */
    @PostConstruct
    void validateIdempotencyTtlConfiguration() {
        if (idempotencyTtlSeconds < MIN_IDEMPOTENCY_TTL_SECONDS
                || idempotencyTtlSeconds > MAX_IDEMPOTENCY_TTL_SECONDS) {
            throw new IllegalStateException("dynamodb.idempotency-ttl-seconds must be between "
                    + MIN_IDEMPOTENCY_TTL_SECONDS + " and " + MAX_IDEMPOTENCY_TTL_SECONDS
                    + " inclusive, got: " + idempotencyTtlSeconds);
        }
    }

    /**
     * Builds the initial {@link PaymentStreamHead} for a new payment partition.
     *
     * <p>Populates head fields used at create time and for merchant GSIs
     * ({@code GSI_MERCHANT_PAYMENTS}, {@code GSI_MERCHANT_STATE_PAYMENTS}), including amounts and ids.
     * {@code reasonCode} stays unset until a terminal rejection updates the head.
     *
     * @param request      original API payload (carries merchantId, amount, currency, etc.)
     * @param paymentId    server-generated id
     * @param correlationId tracing id assigned at creation
     * @param createdAtUtc creation instant (aligned with first event)
     * @return head row with {@code lastSequence=1} and RECEIVED aggregate state
     */
    public PaymentStreamHead toInitialStreamHead(CreateOutboundPaymentRequest request,
                                                 String paymentId,
                                                 String correlationId,
                                                 Instant createdAtUtc) {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(1);
        head.setAggregateState(PaymentState.RECEIVED.name());
        head.setUpdatedAtUtc(createdAtUtc);
        head.setPaymentId(paymentId);
        head.setMerchantId(request.merchantId());
        head.setCreatedAtUtc(createdAtUtc);
        head.setCorrelationId(correlationId);
        head.setAmount(request.amount());
        head.setCurrency(request.currency());
        return head;
    }

    /**
     * First stream event: {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} at sequence 1.
     *
     * @param request       API payload
     * @param paymentId     server id
     * @param correlationId tracing id
     * @param createdAtUtc     event timestamp
     * @return DynamoDB {@link PaymentEvent} item
     */
    public PaymentEvent toOutboundPaymentCreatedEvent(CreateOutboundPaymentRequest request,
                                                      String paymentId,
                                                      String correlationId,
                                                      Instant createdAtUtc) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        event.setEventKey(PaymentEvent.sortKeyForSequence(1));
        event.setEntityType(PaymentEvent.ENTITY_TYPE);
        event.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        event.setSequenceNumber(1);
        event.setCorrelationId(correlationId);
        event.setReasonCode(null);
        event.setOccurredAt(createdAtUtc);
        event.setPaymentId(paymentId);
        event.setMerchantId(request.merchantId());
        event.setDebtorAccountId(request.debtorAccountId());
        event.setCreditorIban(request.creditorIban());
        event.setCreditorName(request.creditorName());
        event.setAmount(request.amount());
        event.setCurrency(request.currency());
        event.setIdempotencyKey(request.idempotencyKey());
        return event;
    }

    /**
     * Builds an {@link IdempotencyRecord} for conditional-write duplicate detection.
     *
     * @param idempotencyKey    client key (stored only as {@code PK} suffix via {@link IdempotencyRecord#KEY_PREFIX})
     * @param requestHash       SHA-256 of the serialized create request
     * @param responseSnapshot  body to return on idempotent retries (includes {@code paymentId})
     * @param createdAtUtc         wall-clock time of first create (aligned with {@code responseSnapshot})
     */
    public IdempotencyRecord toIdempotencyItem(String idempotencyKey,
                                               String requestHash,
                                               CreateOutboundPaymentResponse responseSnapshot,
                                               Instant createdAtUtc) {
        String key = IdempotencyRecord.KEY_PREFIX + idempotencyKey;

        long expiresAtEpochSecond = createdAtUtc.getEpochSecond() + idempotencyTtlSeconds;
        long nowEpoch = Instant.now().getEpochSecond();
        if (expiresAtEpochSecond <= nowEpoch) {
            throw new IllegalStateException(
                    "Idempotency expiresAtEpochSecond must be after now: expiresAtEpochSecond="
                            + expiresAtEpochSecond
                            + ", nowEpochSecond="
                            + nowEpoch);
        }

        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordKey(key);
        record.setEntityKey(IdempotencyRecord.ENTITY_TYPE);
        record.setEntityType(IdempotencyRecord.ENTITY_TYPE);
        record.setRequestHash(requestHash);
        record.setResponseSnapshot(responseSnapshot);
        record.setCreatedAtUtc(createdAtUtc);
        record.setExpiresAtEpochSecond(expiresAtEpochSecond);
        return record;
    }

    /**
     * Projects a replayed {@link Payment} and raw {@link PaymentEvent} list into the GET response.
     */
    public GetOutboundPaymentResponse toGetOutboundPaymentResponse(Payment folded,
                                                                   List<PaymentEvent> events) {
        List<PaymentEventResponse> eventDtos = events.stream()
                .map(this::toPaymentEventResponse)
                .toList();

        return new GetOutboundPaymentResponse(
                folded.getPaymentId(),
                folded.getState(),
                folded.getCorrelationId(),
                folded.getCreatedAtUtc(),
                folded.getUpdatedAtUtc(),
                folded.getDebtorAccountId(),
                folded.getCreditorIban(),
                folded.getCreditorName(),
                folded.getAmount(),
                folded.getCurrency(),
                folded.getIdempotencyKey(),
                folded.getReasonCode(),
                folded.getVersion(),
                eventDtos);
    }

    /**
     * Maps a stored {@link PaymentEvent} to its API representation.
     */
    public PaymentEventResponse toPaymentEventResponse(PaymentEvent event) {
        return new PaymentEventResponse(
                event.getEventKey(),
                event.getEventType(),
                event.getReasonCode(),
                event.getCorrelationId());
    }

    /**
     * Maps a folded {@link Payment} to the process-trigger response.
     */
    public ProcessPaymentResponse toProcessPaymentResponse(Payment payment) {
        return new ProcessPaymentResponse(
                payment.getPaymentId(),
                payment.getState(),
                payment.getReasonCode());
    }

    /**
     * Projects an {@link Account} and its {@link Reservation} items into the GET account response.
     */
    public GetAccountResponse toGetAccountResponse(Account account,
                                                   List<Reservation> reservations) {
        List<ReservationResponse> reservationDtos = reservations.stream()
                .map(this::toReservationResponse)
                .toList();

        return new GetAccountResponse(
                account.getAccountId(),
                account.getStatus(),
                account.getCurrency(),
                account.getCurrentBalance(),
                account.getAvailableBalance(),
                reservationDtos);
    }

    /**
     * Maps a single {@link Reservation} to its API representation.
     */
    public ReservationResponse toReservationResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getReservationId(),
                reservation.getPaymentId(),
                reservation.getAmount(),
                reservation.getStatus(),
                reservation.getCreatedAtUtc());
    }

    /**
     * Projects a {@link PaymentStreamHead} (as returned from a merchant GSI query) into a
     * {@link MerchantPaymentProjection} response DTO.
     *
     * @param head GSI item containing enriched payment projection fields
     * @return response projection with {@code aggregateState} mapped to {@code state}
     *         and {@code lastSequence} mapped to {@code version}
     */
    public MerchantPaymentProjection toMerchantPaymentProjection(PaymentStreamHead head) {
        return new MerchantPaymentProjection(
                head.getPaymentId(),
                head.getAggregateState(),
                head.getLastSequence(),
                head.getMerchantId(),
                head.getCorrelationId(),
                head.getAmount(),
                head.getCurrency(),
                head.getCreatedAtUtc(),
                head.getUpdatedAtUtc(),
                head.getReasonCode());
    }
}
