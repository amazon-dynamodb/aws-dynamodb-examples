package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.LedgerEntry;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Unit tests for {@link OutboundPaymentProcessor}, including happy-path transitions, rejection
 * paths, idempotent no-op states, and conditional transaction conflict handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class OutboundPaymentProcessorTest {

    @Mock
    private PaymentRepository paymentRepository;

    private OutboundPaymentProcessor processor;
    private final PaymentEventReplayer replayer = new PaymentEventReplayer();

    @BeforeEach
    void setUp() {
        processor = new OutboundPaymentProcessor(paymentRepository, replayer);
    }

    @Test
    void processPayment_happyPath_shouldReserveAndComplete() {
        Payment payment = buildPayment("pay_1", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);
        Account accountAfterReserve = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_1"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(
                        adjustPaymentState(payment, PaymentState.FUNDS_RESERVED, 2))));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve));
        when(paymentRepository.reserveFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_1");

        verify(paymentRepository).reserveFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(PaymentEvent.class), eq(new BigDecimal("100")));
        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), eq(new BigDecimal("100")));
    }

    @Test
    void processPayment_accountNotFound_shouldReject() {
        Payment payment = buildPayment("pay_2", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_2"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(PaymentStreamHead.class), any(PaymentEvent.class), eq("ACCOUNT_NOT_FOUND")))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_2");

        verify(paymentRepository).rejectPaymentTransaction(
                any(PaymentStreamHead.class), any(PaymentEvent.class), eq("ACCOUNT_NOT_FOUND"));
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_insufficientFunds_shouldReject() {
        Payment payment = buildPayment("pay_3", "acc_usd_1", new BigDecimal("50000"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_3"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.rejectPaymentTransaction(any(PaymentStreamHead.class), any(PaymentEvent.class), eq("INSUFFICIENT_FUNDS")))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_3");

        verify(paymentRepository).rejectPaymentTransaction(
                any(PaymentStreamHead.class), any(PaymentEvent.class), eq("INSUFFICIENT_FUNDS"));
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_alreadyCompleted_shouldBeNoOp() {
        Payment payment = buildPayment("pay_4", "acc_usd_1", new BigDecimal("100"), PaymentState.COMPLETED, 3);

        when(paymentRepository.queryPaymentPartition("pay_4"))
                .thenReturn(CompletableFuture.completedFuture(completedPartition(payment)));

        processor.processPayment("pay_4");

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any());
        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).rejectPaymentTransaction(any(), any(), any());
    }

    @Test
    void processPayment_alreadyRejected_shouldBeNoOp() {
        Payment payment = buildPayment("pay_5", "acc_usd_1", new BigDecimal("100"), PaymentState.REJECTED, 2);

        when(paymentRepository.queryPaymentPartition("pay_5"))
                .thenReturn(CompletableFuture.completedFuture(rejectedPartition(payment)));

        processor.processPayment("pay_5");

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any());
        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).rejectPaymentTransaction(any(), any(), any());
    }

    @Test
    void processPayment_fundsReserved_shouldResumeToComplete() {
        Payment payment = buildPayment("pay_6", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_6"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_6");

        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any());
        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class));
    }

    @Test
    void processPayment_notFound_shouldThrow() {
        when(paymentRepository.queryPaymentPartition("pay_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> processor.processPayment("pay_unknown"))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("pay_unknown");
    }

    @Test
    void processPayment_reserveConflict_paymentAlreadyCompleted_shouldBeNoOp() {
        Payment received = buildPayment("pay_7", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Payment completedPayment = buildPayment("pay_7", "acc_usd_1", new BigDecimal("100"), PaymentState.COMPLETED, 3);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        TransactionCanceledException tce = cancellationFailedAtIndex(2, 4);

        when(paymentRepository.queryPaymentPartition("pay_7"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(completedPartition(completedPayment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));

        processor.processPayment("pay_7");

        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_reserveConflict_reReadFundsReserved_shouldComplete() {
        Payment received = buildPayment("pay_8", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Payment fundsReserved = buildPayment("pay_8", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_8"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(2, 4)));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_8");

        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class));
    }

    @Test
    void processPayment_completeConflict_shouldSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_9", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_9"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(3, 5)));

        processor.processPayment("pay_9");

        verify(paymentRepository).completeFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_completeConflictOnAccountVersion_shouldAlsoSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_9b", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_9b"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(0, 5)));

        processor.processPayment("pay_9b");

        verify(paymentRepository).completeFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_rejectConflict_shouldSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_10", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_10"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(), any(), eq("ACCOUNT_NOT_FOUND")))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(1, 2)));

        processor.processPayment("pay_10");

        verify(paymentRepository).rejectPaymentTransaction(any(), any(), eq("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void processPayment_reserveConflict_unexpectedState_shouldNotComplete() {
        Payment received = buildPayment("pay_11", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_11"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(2, 4)));

        processor.processPayment("pay_11");

        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_reserveNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_12", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_12"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network")));

        assertThatThrownBy(() -> processor.processPayment("pay_12"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reserve transaction failed");
    }

    @Test
    void processPayment_completeNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_13", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_13"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("throttle")));

        assertThatThrownBy(() -> processor.processPayment("pay_13"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Complete transaction failed");
    }

    @Test
    void processPayment_rejectNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_14", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_14"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("throttle")));

        assertThatThrownBy(() -> processor.processPayment("pay_14"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Reject transaction failed");
    }

    private static TransactionCanceledException cancellationFailedAtIndex(int failedIndex, int size) {
        List<CancellationReason> reasons = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            reasons.add(CancellationReason.builder()
                    .code(i == failedIndex ? "ConditionalCheckFailed" : "None")
                    .build());
        }
        return TransactionCanceledException.builder()
                .cancellationReasons(reasons)
                .message("Transaction cancelled")
                .build();
    }

    private Payment buildPayment(String paymentId, String debtorAccountId, BigDecimal amount, PaymentState state, int version) {
        Instant createdAt = Instant.parse("2024-01-15T10:00:00Z");
        Payment p = new Payment();
        p.setPaymentKey("PAYMENT#" + paymentId);
        p.setPaymentId(paymentId);
        p.setMerchantId("merch_1");
        p.setState(state.name());
        p.setDebtorAccountId(debtorAccountId);
        p.setCreditorIban("RO49AAAA1B31007593840000");
        p.setCreditorName("Test");
        p.setAmount(amount);
        p.setCurrency("USD");
        p.setIdempotencyKey("idem_" + paymentId);
        p.setCorrelationId("corr_test");
        p.setCreatedAtUtc(createdAt);
        p.setUpdatedAtUtc(createdAt);
        p.setVersion(version);
        if (state == PaymentState.REJECTED) {
            p.setReasonCode("TEST_REJECT");
        }
        return p;
    }

    private static Payment adjustPaymentState(Payment template, PaymentState state, int version) {
        Payment p = new Payment();
        p.setPaymentKey(template.getPaymentKey());
        p.setPaymentId(template.getPaymentId());
        p.setMerchantId(template.getMerchantId());
        p.setState(state.name());
        p.setDebtorAccountId(template.getDebtorAccountId());
        p.setCreditorIban(template.getCreditorIban());
        p.setCreditorName(template.getCreditorName());
        p.setAmount(template.getAmount());
        p.setCurrency(template.getCurrency());
        p.setIdempotencyKey(template.getIdempotencyKey());
        p.setCorrelationId(template.getCorrelationId());
        p.setCreatedAtUtc(template.getCreatedAtUtc());
        p.setUpdatedAtUtc(template.getUpdatedAtUtc());
        p.setVersion(version);
        return p;
    }

    private PaymentPartitionQueryResult createdOnlyPartition(Payment foldedFromReplay) {
        String paymentId = foldedFromReplay.getPaymentId();
        Instant t = foldedFromReplay.getCreatedAtUtc();
        PaymentStreamHead head = baseHead(paymentId, 1, PaymentState.RECEIVED.name(), t);
        PaymentEvent e1 = createdEvent(foldedFromReplay, t);
        return new PaymentPartitionQueryResult(head, List.of(e1));
    }

    private PaymentPartitionQueryResult fundsReservedPartition(Payment folded) {
        String paymentId = folded.getPaymentId();
        Instant t0 = folded.getCreatedAtUtc();
        Instant t1 = t0.plusSeconds(1);
        PaymentStreamHead head = baseHead(paymentId, 2, PaymentState.FUNDS_RESERVED.name(), t1);
        PaymentEvent e1 = createdEvent(folded, t0);
        PaymentEvent e2 = new PaymentEvent();
        e2.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        e2.setEventKey(PaymentEvent.sortKeyForSequence(2));
        e2.setEntityType(PaymentEvent.ENTITY_TYPE);
        e2.setEventType(PaymentEventType.FUNDS_RESERVED.name());
        e2.setSequenceNumber(2);
        e2.setCorrelationId(folded.getCorrelationId());
        e2.setOccurredAt(t1);
        return new PaymentPartitionQueryResult(head, List.of(e1, e2));
    }

    private PaymentPartitionQueryResult completedPartition(Payment folded) {
        String paymentId = folded.getPaymentId();
        Instant t0 = folded.getCreatedAtUtc();
        Instant t1 = t0.plusSeconds(1);
        Instant t2 = t0.plusSeconds(2);
        PaymentStreamHead head = baseHead(paymentId, 3, PaymentState.COMPLETED.name(), t2);
        PaymentEvent e1 = createdEvent(folded, t0);
        PaymentEvent e2 = reserveEvent(folded, t1);
        PaymentEvent e3 = new PaymentEvent();
        e3.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        e3.setEventKey(PaymentEvent.sortKeyForSequence(3));
        e3.setEntityType(PaymentEvent.ENTITY_TYPE);
        e3.setEventType(PaymentEventType.COMPLETED.name());
        e3.setSequenceNumber(3);
        e3.setCorrelationId(folded.getCorrelationId());
        e3.setOccurredAt(t2);
        return new PaymentPartitionQueryResult(head, List.of(e1, e2, e3));
    }

    private PaymentPartitionQueryResult rejectedPartition(Payment folded) {
        String paymentId = folded.getPaymentId();
        Instant t0 = folded.getCreatedAtUtc();
        Instant t1 = t0.plusSeconds(1);
        PaymentStreamHead head = baseHead(paymentId, 2, PaymentState.REJECTED.name(), t1);
        PaymentEvent e1 = createdEvent(folded, t0);
        PaymentEvent e2 = new PaymentEvent();
        e2.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        e2.setEventKey(PaymentEvent.sortKeyForSequence(2));
        e2.setEntityType(PaymentEvent.ENTITY_TYPE);
        e2.setEventType(PaymentEventType.REJECTED.name());
        e2.setSequenceNumber(2);
        e2.setReasonCode(folded.getReasonCode());
        e2.setCorrelationId(folded.getCorrelationId());
        e2.setOccurredAt(t1);
        return new PaymentPartitionQueryResult(head, List.of(e1, e2));
    }

    private static PaymentStreamHead baseHead(String paymentId, long seq, String state, Instant updated) {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(seq);
        head.setAggregateState(state);
        head.setUpdatedAtUtc(updated);
        head.setPaymentId(paymentId);
        head.setMerchantId("merch_1");
        head.setCreatedAtUtc(updated);
        head.setCorrelationId("corr_test");
        head.setAmount(new BigDecimal("100"));
        head.setCurrency("USD");
        return head;
    }

    private static PaymentEvent createdEvent(Payment folded, Instant t) {
        String paymentId = folded.getPaymentId();
        PaymentEvent e1 = new PaymentEvent();
        e1.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        e1.setEventKey(PaymentEvent.sortKeyForSequence(1));
        e1.setEntityType(PaymentEvent.ENTITY_TYPE);
        e1.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        e1.setSequenceNumber(1);
        e1.setCorrelationId(folded.getCorrelationId());
        e1.setOccurredAt(t);
        e1.setPaymentId(paymentId);
        e1.setMerchantId(folded.getMerchantId());
        e1.setDebtorAccountId(folded.getDebtorAccountId());
        e1.setCreditorIban(folded.getCreditorIban());
        e1.setCreditorName(folded.getCreditorName());
        e1.setAmount(folded.getAmount());
        e1.setCurrency(folded.getCurrency());
        e1.setIdempotencyKey(folded.getIdempotencyKey());
        return e1;
    }

    private static PaymentEvent reserveEvent(Payment folded, Instant t) {
        String paymentId = folded.getPaymentId();
        PaymentEvent e2 = new PaymentEvent();
        e2.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        e2.setEventKey(PaymentEvent.sortKeyForSequence(2));
        e2.setEntityType(PaymentEvent.ENTITY_TYPE);
        e2.setEventType(PaymentEventType.FUNDS_RESERVED.name());
        e2.setSequenceNumber(2);
        e2.setCorrelationId(folded.getCorrelationId());
        e2.setOccurredAt(t);
        return e2;
    }

    private Account buildAccount(String accountId, BigDecimal currentBalance, BigDecimal availableBalance, int version) {
        String key = "ACCOUNT#" + accountId;
        Account a = new Account();
        a.setAccountKey(key);
        a.setEntityKey(key);
        a.setEntityType("ACCOUNT");
        a.setAccountId(accountId);
        a.setStatus("ACTIVE");
        a.setCurrentBalance(currentBalance);
        a.setAvailableBalance(availableBalance);
        a.setCurrency("USD");
        a.setVersion(version);
        return a;
    }
}
