package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * Unit tests for {@link PaymentEventReplayer}: valid folds, guard rails on ordering and transitions.
 */
@Tag("unit")
public class PaymentEventReplayerTest {

    private static final String PAYMENT_ID = "pay_replay_1";

    private static final String PAYMENT_KEY = Payment.KEY_PREFIX + PAYMENT_ID;

    private static final Instant T0 = Instant.parse("2025-06-01T10:00:00Z");

    private static final Instant T1 = Instant.parse("2025-06-01T10:00:01Z");

    private static final Instant T2 = Instant.parse("2025-06-01T10:00:02Z");

    private final PaymentEventReplayer replayer = new PaymentEventReplayer();

    @Test
    void fold_whenEventsEmpty_shouldThrow() {
        assertThatThrownBy(() -> replayer.fold(PAYMENT_ID, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot fold empty event stream");
    }

    @Test
    void fold_whenCreatedOnly_shouldSucceed() {
        List<PaymentEvent> events = List.of(created(1, T0));
        Payment p = replayer.fold(PAYMENT_ID, events);

        assertThat(p.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(p.getMerchantId()).isEqualTo("merch_1");
        assertThat(p.getState()).isEqualTo(PaymentState.RECEIVED.name());
        assertThat(p.getVersion()).isEqualTo(1);
        assertThat(p.getAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void fold_whenFullHappyPath_shouldReachCreatedReservedCompleted() {
        List<PaymentEvent> events = List.of(
                created(1, T0),
                fundsReserved(2, T1),
                completed(3, T2));

        Payment p = replayer.fold(PAYMENT_ID, events);

        assertThat(p.getState()).isEqualTo(PaymentState.COMPLETED.name());
        assertThat(p.getVersion()).isEqualTo(3);
        assertThat(p.getUpdatedAtUtc()).isEqualTo(T2);
    }

    @Test
    void fold_whenRejectedFromReceived_shouldReachRejected() {
        List<PaymentEvent> events = List.of(
                created(1, T0),
                rejected(2, T1, "INSUFFICIENT_FUNDS"));

        Payment p = replayer.fold(PAYMENT_ID, events);

        assertThat(p.getState()).isEqualTo(PaymentState.REJECTED.name());
        assertThat(p.getReasonCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(p.getVersion()).isEqualTo(2);
    }

    @Test
    void fold_whenRejectedFromFundsReserved_shouldReachRejected() {
        List<PaymentEvent> events = List.of(
                created(1, T0),
                fundsReserved(2, T1),
                rejected(3, T2, "RISK_BLOCKED"));

        Payment p = replayer.fold(PAYMENT_ID, events);

        assertThat(p.getState()).isEqualTo(PaymentState.REJECTED.name());
        assertThat(p.getReasonCode()).isEqualTo("RISK_BLOCKED");
        assertThat(p.getVersion()).isEqualTo(3);
    }

    @Test
    void fold_whenSequenceGap_shouldThrow() {
        List<PaymentEvent> events = List.of(created(1, T0), fundsReserved(3, T1));

        assertThatThrownBy(() -> replayer.fold(PAYMENT_ID, events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected sequence 2");
    }

    @Test
    void fold_whenCompletedAfterReceivedSkipsReserve_shouldThrow() {
        List<PaymentEvent> events = List.of(created(1, T0), completed(2, T1));

        assertThatThrownBy(() -> replayer.fold(PAYMENT_ID, events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED requires FUNDS_RESERVED");
    }

    @Test
    void fold_whenFundsReservedWithoutCreated_shouldThrow() {
        List<PaymentEvent> events = List.of(fundsReserved(1, T0));

        assertThatThrownBy(() -> replayer.fold(PAYMENT_ID, events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing prior state");
    }

    @Test
    void fold_whenInputListUnordered_shouldThrow() {
        List<PaymentEvent> events = new ArrayList<>(List.of(
                created(1, T0),
                fundsReserved(2, T1),
                completed(3, T2)));
        Collections.swap(events, 0, 2);

        assertThatThrownBy(() -> replayer.fold(PAYMENT_ID, events))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected sequence");
    }

    /** Builds an OUTBOUND_PAYMENT_CREATED event at the given sequence and time. */
    private static PaymentEvent created(long seq, Instant at) {
        PaymentEvent e = new PaymentEvent();
        e.setPaymentKey(PAYMENT_KEY);
        e.setEventKey(PaymentEvent.sortKeyForSequence(seq));
        e.setEntityType(PaymentEvent.ENTITY_TYPE);
        e.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        e.setSequenceNumber(seq);
        e.setCorrelationId("corr_1");
        e.setReasonCode(null);
        e.setOccurredAt(at);
        e.setPaymentId(PAYMENT_ID);
        e.setMerchantId("merch_1");
        e.setDebtorAccountId("acc_1");
        e.setCreditorIban("RO00TEST");
        e.setCreditorName("ACME");
        e.setAmount(new BigDecimal("10.00"));
        e.setCurrency("USD");
        e.setIdempotencyKey("idem_1");
        return e;
    }

    /** Builds a FUNDS_RESERVED event at the given sequence and time. */
    private static PaymentEvent fundsReserved(long seq, Instant at) {
        PaymentEvent e = new PaymentEvent();
        e.setPaymentKey(PAYMENT_KEY);
        e.setEventKey(PaymentEvent.sortKeyForSequence(seq));
        e.setEntityType(PaymentEvent.ENTITY_TYPE);
        e.setEventType(PaymentEventType.FUNDS_RESERVED.name());
        e.setSequenceNumber(seq);
        e.setCorrelationId("corr_1");
        e.setReasonCode(null);
        e.setOccurredAt(at);
        return e;
    }

    /** Builds a COMPLETED event at the given sequence and time. */
    private static PaymentEvent completed(long seq, Instant at) {
        PaymentEvent e = new PaymentEvent();
        e.setPaymentKey(PAYMENT_KEY);
        e.setEventKey(PaymentEvent.sortKeyForSequence(seq));
        e.setEntityType(PaymentEvent.ENTITY_TYPE);
        e.setEventType(PaymentEventType.COMPLETED.name());
        e.setSequenceNumber(seq);
        e.setCorrelationId("corr_1");
        e.setReasonCode(null);
        e.setOccurredAt(at);
        return e;
    }

    /** Builds a REJECTED event with the given sequence, time, and reason code. */
    private static PaymentEvent rejected(long seq, Instant at, String reason) {
        PaymentEvent e = new PaymentEvent();
        e.setPaymentKey(PAYMENT_KEY);
        e.setEventKey(PaymentEvent.sortKeyForSequence(seq));
        e.setEntityType(PaymentEvent.ENTITY_TYPE);
        e.setEventType(PaymentEventType.REJECTED.name());
        e.setSequenceNumber(seq);
        e.setCorrelationId("corr_1");
        e.setReasonCode(reason);
        e.setOccurredAt(at);
        return e;
    }
}
