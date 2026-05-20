package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.util.List;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;

/**
 * Pure fold over ordered {@link PaymentEvent} records into an in-memory {@link Payment} view.
 *
 * <p>Used for command decisions and read models. DynamoDB persistence uses the same events
 * plus {@link PaymentStreamHead}
 * for compare-and-append.
 *
 * <p><strong>State machine:</strong> {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} seeds
 * {@link PaymentState#RECEIVED}. {@link PaymentEventType#FUNDS_RESERVED} requires {@code RECEIVED}.
 * {@link PaymentEventType#COMPLETED} requires {@code FUNDS_RESERVED}. {@link PaymentEventType#REJECTED}
 * is allowed from {@code RECEIVED} or {@code FUNDS_RESERVED}. Invalid pairs throw {@link IllegalStateException}.
 *
 * <p><strong>Version:</strong> After each applied event, {@link Payment#getVersion()} equals that event's
 * {@link PaymentEvent#getSequenceNumber()}, matching {@link PaymentStreamHead#getLastSequence()}.
 */
@Component
public class PaymentEventReplayer {

    /**
     * Replays chronologically ordered events (by {@link PaymentEvent#getSequenceNumber()}).
     *
     * @param paymentId logical id (without {@link Payment#KEY_PREFIX})
     * @param events    non-empty history ending at {@code lastSequence}
     * @return folded aggregate
     * @throws IllegalStateException if the sequence is broken or transitions are invalid
     *
     * @implSpec Expects contiguous sequence numbers starting at {@code 1}, in list iteration order
     *     (same order as a partition {@code Query} with ascending sort key). Gaps or duplicates fail fast.
     */
    public Payment fold(String paymentId, List<PaymentEvent> events) {
        if (events.isEmpty()) {
            throw new IllegalStateException("Cannot fold empty event stream for payment " + paymentId);
        }
        Payment aggregate = null;
        long expectedSeq = 1;
        for (PaymentEvent event : events) {
            if (event.getSequenceNumber() != expectedSeq) {
                throw new IllegalStateException(
                        "Expected sequence " + expectedSeq + " but got " + event.getSequenceNumber());
            }
            aggregate = apply(aggregate, event);
            expectedSeq++;
        }
        return aggregate;
    }

    /**
     * Applies one event to the running aggregate, dispatching on {@link PaymentEventType}.
     *
     * @param aggregate state before this event ({@code null} only for sequence 1)
     * @param event     next event in order
     * @return aggregate after the event
     */
    private Payment apply(Payment aggregate, PaymentEvent event) {
        PaymentEventType type = PaymentEventType.valueOf(event.getEventType());
        return switch (type) {
            case OUTBOUND_PAYMENT_CREATED -> applyCreated(event);
            case FUNDS_RESERVED -> applyReserved(requireAggregate(aggregate, event), event);
            case COMPLETED -> applyCompleted(requireAggregate(aggregate, event), event);
            case REJECTED -> applyRejected(requireAggregate(aggregate, event), event);
        };
    }

    /**
     * Builds the initial aggregate from {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED}.
     */
    private static Payment applyCreated(PaymentEvent event) {
        Payment p = new Payment();
        p.setPaymentKey(event.getPaymentKey());
        p.setPaymentId(event.getPaymentId());
        p.setMerchantId(event.getMerchantId());
        p.setDebtorAccountId(event.getDebtorAccountId());
        p.setCreditorIban(event.getCreditorIban());
        p.setCreditorName(event.getCreditorName());
        p.setAmount(event.getAmount());
        p.setCurrency(event.getCurrency());
        p.setIdempotencyKey(event.getIdempotencyKey());
        p.setCorrelationId(event.getCorrelationId());
        p.setCreatedAtUtc(event.getOccurredAt());
        p.setState(PaymentState.RECEIVED.name());
        p.setReasonCode(null);
        p.setUpdatedAtUtc(event.getOccurredAt());
        p.setVersion(1);
        return p;
    }

    /**
     * @throws IllegalStateException if {@code aggregate} is {@code null} (illegal for non-create events)
     */
    private static Payment requireAggregate(Payment aggregate, PaymentEvent event) {
        if (aggregate == null) {
            throw new IllegalStateException("Missing prior state before event " + event.getEventType());
        }
        return aggregate;
    }

    /**
     * Transition {@link PaymentState#RECEIVED} → {@link PaymentState#FUNDS_RESERVED}.
     */
    private static Payment applyReserved(Payment p, PaymentEvent event) {
        if (!PaymentState.RECEIVED.name().equals(p.getState())) {
            throw new IllegalStateException("FUNDS_RESERVED requires RECEIVED, was " + p.getState());
        }
        Payment copy = copyShell(p);
        copy.setState(PaymentState.FUNDS_RESERVED.name());
        copy.setUpdatedAtUtc(event.getOccurredAt());
        copy.setVersion((int) event.getSequenceNumber());
        return copy;
    }

    /**
     * Transition {@link PaymentState#FUNDS_RESERVED} → {@link PaymentState#COMPLETED}.
     */
    private static Payment applyCompleted(Payment p, PaymentEvent event) {
        if (!PaymentState.FUNDS_RESERVED.name().equals(p.getState())) {
            throw new IllegalStateException("COMPLETED requires FUNDS_RESERVED, was " + p.getState());
        }
        Payment copy = copyShell(p);
        copy.setState(PaymentState.COMPLETED.name());
        copy.setUpdatedAtUtc(event.getOccurredAt());
        copy.setVersion((int) event.getSequenceNumber());
        return copy;
    }

    /**
     * Terminal failure from {@link PaymentState#RECEIVED} or {@link PaymentState#FUNDS_RESERVED} to {@link PaymentState#REJECTED}.
     */
    private static Payment applyRejected(Payment p, PaymentEvent event) {
        if (!PaymentState.RECEIVED.name().equals(p.getState())
                && !PaymentState.FUNDS_RESERVED.name().equals(p.getState())) {
            throw new IllegalStateException(
                    "REJECTED requires RECEIVED or FUNDS_RESERVED, was " + p.getState());
        }
        Payment copy = copyShell(p);
        copy.setState(PaymentState.REJECTED.name());
        copy.setReasonCode(event.getReasonCode());
        copy.setUpdatedAtUtc(event.getOccurredAt());
        copy.setVersion((int) event.getSequenceNumber());
        return copy;
    }

    /**
     * Shallow copy of scalar fields before mutating state for the next event.
     */
    private static Payment copyShell(Payment p) {
        Payment c = new Payment();
        c.setPaymentKey(p.getPaymentKey());
        c.setPaymentId(p.getPaymentId());
        c.setMerchantId(p.getMerchantId());
        c.setDebtorAccountId(p.getDebtorAccountId());
        c.setCreditorIban(p.getCreditorIban());
        c.setCreditorName(p.getCreditorName());
        c.setAmount(p.getAmount());
        c.setCurrency(p.getCurrency());
        c.setIdempotencyKey(p.getIdempotencyKey());
        c.setCorrelationId(p.getCorrelationId());
        c.setCreatedAtUtc(p.getCreatedAtUtc());
        c.setState(p.getState());
        c.setReasonCode(p.getReasonCode());
        c.setUpdatedAtUtc(p.getUpdatedAtUtc());
        c.setVersion(p.getVersion());
        return c;
    }
}
