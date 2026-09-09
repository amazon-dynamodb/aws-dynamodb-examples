package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor.MAX_TRANSACTION_CONFLICT_RETRIES;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.AsyncSupport;
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

    private ScheduledExecutorService delayScheduler;

    private final PaymentEventReplayer replayer = new PaymentEventReplayer();

    private static final long RESERVATION_TIMEOUT_SECONDS = 900L;

    /** Constructs the processor with repository and replayer collaborators. */
    @BeforeEach
    void setUp() {
        delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "async-delay-processor-test");
            thread.setDaemon(true);
            return thread;
        });
        AsyncSupport asyncSupport = new AsyncSupport(delayScheduler);
        processor = new OutboundPaymentProcessor(
                paymentRepository, replayer, asyncSupport, RESERVATION_TIMEOUT_SECONDS);
    }

    /** Shuts down the delay scheduler so test threads do not leak between cases. */
    @AfterEach
    void tearDown() {
        delayScheduler.shutdownNow();
    }

    @Test
    void processPayment_whenHappyPath_shouldReserveAndComplete() {
        Payment payment = buildPayment("pay_1", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);
        Account accountAfterReserve = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        // Single partition load on the happy path: the post-reserve head is threaded forward to complete.
        when(paymentRepository.queryPaymentPartition("pay_1"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve));
        when(paymentRepository.reserveFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_1").join();

        // The partition is queried exactly once: reserve advances the head in memory and threads it to complete.
        verify(paymentRepository, times(1)).queryPaymentPartition("pay_1");
        verify(paymentRepository).reserveFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(PaymentEvent.class), eq(new BigDecimal("100")));
        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), eq(new BigDecimal("100")));
    }

    @Test
    void processPayment_whenHappyPath_shouldStampAuditAndTemporaryReservation() {
        Payment payment = buildPayment("pay_expiry", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);
        Account accountAfterReserve = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_expiry"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        long beforeEpoch = Instant.now().getEpochSecond();
        processor.processPayment("pay_expiry").join();
        long afterEpoch = Instant.now().getEpochSecond();

        ArgumentCaptor<Reservation> auditCaptor = ArgumentCaptor.forClass(Reservation.class);
        ArgumentCaptor<Reservation> temporaryReservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        verify(paymentRepository).reserveFundsTransaction(
                any(PaymentStreamHead.class), eq(account), auditCaptor.capture(),
                temporaryReservationCaptor.capture(), any(PaymentEvent.class), eq(new BigDecimal("100")));

        Reservation reservation = auditCaptor.getValue();
        assertThat(reservation.getReservationId()).isEqualTo("res_pay_expiry");
        assertThat(reservation.getReservationKey()).isEqualTo(Reservation.KEY_PREFIX + "res_pay_expiry");
        assertThat(reservation.getEntityType()).isEqualTo(Reservation.ENTITY_TYPE);
        assertThat(reservation.getPaymentId()).isEqualTo("pay_expiry");
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE.name());
        assertThat(reservation.getCreatedAtUtc()).isNotNull();
        assertThat(reservation.getTtl()).isNull();

        // The temporary reservation is a minimal timer: keys, entityType discriminator, and the ttl deadline only.
        Reservation temporaryReservation = temporaryReservationCaptor.getValue();
        assertThat(temporaryReservation.getReservationKey()).isEqualTo(Reservation.TEMPORARY_KEY_PREFIX + "res_pay_expiry");
        assertThat(temporaryReservation.getEntityType()).isEqualTo(Reservation.TEMPORARY_ENTITY_TYPE);
        assertThat(temporaryReservation.getReservationId()).isNull();
        assertThat(temporaryReservation.getPaymentId()).isNull();
        assertThat(temporaryReservation.getStatus()).isNull();
        assertThat(temporaryReservation.getCreatedAtUtc()).isNull();
        assertThat(temporaryReservation.getTtl())
                .isBetween(beforeEpoch + RESERVATION_TIMEOUT_SECONDS, afterEpoch + RESERVATION_TIMEOUT_SECONDS);
    }

    @Test
    void processPayment_whenAccountNotFound_shouldReject() {
        Payment payment = buildPayment("pay_2", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_2"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(PaymentStreamHead.class), any(PaymentEvent.class), eq("ACCOUNT_NOT_FOUND")))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_2").join();

        verify(paymentRepository).rejectPaymentTransaction(
                any(PaymentStreamHead.class), any(PaymentEvent.class), eq("ACCOUNT_NOT_FOUND"));
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenInsufficientFunds_shouldReject() {
        Payment payment = buildPayment("pay_3", "acc_usd_1", new BigDecimal("50000"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_3"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.rejectPaymentTransaction(any(PaymentStreamHead.class), any(PaymentEvent.class), eq("INSUFFICIENT_FUNDS")))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_3").join();

        verify(paymentRepository).rejectPaymentTransaction(
                any(PaymentStreamHead.class), any(PaymentEvent.class), eq("INSUFFICIENT_FUNDS"));
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenAlreadyCompleted_shouldBeNoOp() {
        Payment payment = buildPayment("pay_4", "acc_usd_1", new BigDecimal("100"), PaymentState.COMPLETED, 3);

        when(paymentRepository.queryPaymentPartition("pay_4"))
                .thenReturn(CompletableFuture.completedFuture(completedPartition(payment)));

        processor.processPayment("pay_4").join();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).rejectPaymentTransaction(any(), any(), any());
    }

    @Test
    void processPayment_whenAlreadyRejected_shouldBeNoOp() {
        Payment payment = buildPayment("pay_5", "acc_usd_1", new BigDecimal("100"), PaymentState.REJECTED, 2);

        when(paymentRepository.queryPaymentPartition("pay_5"))
                .thenReturn(CompletableFuture.completedFuture(rejectedPartition(payment)));

        processor.processPayment("pay_5").join();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
        verify(paymentRepository, never()).rejectPaymentTransaction(any(), any(), any());
    }

    @Test
    void processPayment_whenFundsReserved_shouldResumeToComplete() {
        Payment payment = buildPayment("pay_6", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_6"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_6").join();

        verify(paymentRepository, never()).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class));
    }

    @Test
    void processPayment_whenPaymentNotFound_shouldThrow() {
        when(paymentRepository.queryPaymentPartition("pay_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> processor.processPayment("pay_unknown").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(PaymentNotFoundException.class)
                        .hasMessageContaining("pay_unknown"));
    }

    @Test
    void getPayment_whenPartitionReadIsTransientlySkewed_shouldRereadUntilConsistent() {
        Payment payment = buildPayment("pay_skew_once", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Instant createdAt = payment.getCreatedAtUtc();
        PaymentPartitionQueryResult skewed = new PaymentPartitionQueryResult(
                baseHead("pay_skew_once", 2, PaymentState.FUNDS_RESERVED.name(), createdAt.plusSeconds(1)),
                List.of(createdEvent(payment, createdAt)));

        when(paymentRepository.queryPaymentPartition("pay_skew_once"))
                .thenReturn(CompletableFuture.completedFuture(skewed))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));

        Payment folded = processor.getPayment("pay_skew_once").join();

        assertThat(folded.getPaymentId()).isEqualTo("pay_skew_once");
        assertThat(folded.getState()).isEqualTo(PaymentState.RECEIVED.name());
        verify(paymentRepository, times(2)).queryPaymentPartition("pay_skew_once");
    }

    @Test
    void getPayment_whenPartitionReadSkewPersists_shouldThrow() {
        Payment payment = buildPayment("pay_skew_stuck", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Instant createdAt = payment.getCreatedAtUtc();
        PaymentPartitionQueryResult skewed = new PaymentPartitionQueryResult(
                baseHead("pay_skew_stuck", 2, PaymentState.FUNDS_RESERVED.name(), createdAt.plusSeconds(1)),
                List.of(createdEvent(payment, createdAt)));

        when(paymentRepository.queryPaymentPartition("pay_skew_stuck"))
                .thenReturn(CompletableFuture.completedFuture(skewed));

        assertThatThrownBy(() -> processor.getPayment("pay_skew_stuck").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Stream head does not match replayed aggregate"));

        verify(paymentRepository, times(5)).queryPaymentPartition("pay_skew_stuck");
    }

    @Test
    void processPayment_whenReserveConflictAndPaymentAlreadyCompleted_shouldBeNoOp() {
        Payment received = buildPayment("pay_7", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Payment completedPayment = buildPayment("pay_7", "acc_usd_1", new BigDecimal("100"), PaymentState.COMPLETED, 3);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        TransactionCanceledException tce = cancellationFailedAtIndex(2, 4);

        when(paymentRepository.queryPaymentPartition("pay_7"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(completedPartition(completedPayment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));

        processor.processPayment("pay_7").join();

        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenReserveConflictAndReReadFundsReserved_shouldComplete() {
        Payment received = buildPayment("pay_8", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Payment fundsReserved = buildPayment("pay_8", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_8"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(2, 4)));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_8").join();

        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class));
    }

    @Test
    void processPayment_whenCompleteConflict_shouldSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_9", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_9"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(3, 5)));

        processor.processPayment("pay_9").join();

        verify(paymentRepository).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenCompleteConflictOnAccountVersion_shouldAlsoSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_9b", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_9b"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(0, 5)));

        processor.processPayment("pay_9b").join();

        verify(paymentRepository).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenCompleteAndHoldExpired_shouldRejectWithReservationExpired() {
        Payment payment = buildPayment("pay_exp_reject", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_exp_reject"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        // Only the reservation consume item (index 1) fails: the hold passed its deadline while the head still matched.
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(1, 6)));
        when(paymentRepository.rejectPaymentTransaction(any(), any(), eq("RESERVATION_EXPIRED")))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_exp_reject").join();

        verify(paymentRepository).rejectPaymentTransaction(any(), any(), eq("RESERVATION_EXPIRED"));
    }

    @Test
    void processPayment_whenCompleteAndHeadAdvanced_shouldNoOpWithoutRejecting() {
        Payment payment = buildPayment("pay_exp_noop", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_exp_noop"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        // The stream head item (index 4) failed: another processor already advanced the payment, so this is a no-op.
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(4, 6)));

        processor.processPayment("pay_exp_noop").join();

        verify(paymentRepository, never()).rejectPaymentTransaction(any(), any(), any());
    }

    @Test
    void processPayment_whenRejectConflict_shouldSwallowConditionalFailure() {
        Payment payment = buildPayment("pay_10", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_10"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(), any(), eq("ACCOUNT_NOT_FOUND")))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(1, 2)));

        processor.processPayment("pay_10").join();

        verify(paymentRepository).rejectPaymentTransaction(any(), any(), eq("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void processPayment_whenReserveConflictAndUnexpectedState_shouldNotComplete() {
        Payment received = buildPayment("pay_11", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_11"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(cancellationFailedAtIndex(2, 4)));

        processor.processPayment("pay_11").join();

        verify(paymentRepository, never()).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenReserveNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_12", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_12"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("network")));

        assertThatThrownBy(() -> processor.processPayment("pay_12").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Reserve transaction failed"));
    }

    @Test
    void processPayment_whenCompleteNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_13", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_13"))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(payment)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("throttle")));

        assertThatThrownBy(() -> processor.processPayment("pay_13").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Complete transaction failed"));
    }

    @Test
    void processPayment_whenRejectNonConditionalFailure_shouldThrow() {
        Payment payment = buildPayment("pay_14", "acc_unknown", new BigDecimal("100"), PaymentState.RECEIVED, 1);

        when(paymentRepository.queryPaymentPartition("pay_14"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(payment)));
        when(paymentRepository.getAccount("acc_unknown"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.rejectPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("throttle")));

        assertThatThrownBy(() -> processor.processPayment("pay_14").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Reject transaction failed"));
    }

    @Test
    void processPayment_whenReserveTransactionConflictThenSuccess_shouldRetryAndComplete() {
        Payment received = buildPayment("pay_15", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);
        Account accountAfterReserve = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        // Attempt 1 reloads RECEIVED and the reserve conflicts.
        // Attempt 2 reloads RECEIVED, reserves, then completes against the threaded-forward head.
        when(paymentRepository.queryPaymentPartition("pay_15"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(buildTransactionConflictCancellation(4)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_15").join();

        verify(paymentRepository, times(2)).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository).completeFundsTransaction(
                any(PaymentStreamHead.class), any(Account.class), any(Reservation.class),
                any(Reservation.class), any(LedgerEntry.class), any(PaymentEvent.class), any(BigDecimal.class));
    }

    @Test
    void processPayment_whenReserveTransactionConflictNeverResolves_shouldExhaustRetriesAndThrow() {
        Payment received = buildPayment("pay_16", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);

        when(paymentRepository.queryPaymentPartition("pay_16"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(buildTransactionConflictCancellation(4)));

        assertThatThrownBy(() -> processor.processPayment("pay_16").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Transaction conflict retries exhausted"));

        // Initial attempt plus the capped retries.
        verify(paymentRepository, times(MAX_TRANSACTION_CONFLICT_RETRIES + 1))
                .reserveFundsTransaction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void processPayment_whenCompleteTransactionConflictThenSuccess_shouldRetryWholeFlowAndComplete() {
        Payment received = buildPayment("pay_17", "acc_usd_1", new BigDecimal("100"), PaymentState.RECEIVED, 1);
        Payment fundsReserved = buildPayment("pay_17", "acc_usd_1", new BigDecimal("100"), PaymentState.FUNDS_RESERVED, 2);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("10000"), 1);
        Account accountAfterReserve = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);

        when(paymentRepository.queryPaymentPartition("pay_17"))
                .thenReturn(CompletableFuture.completedFuture(createdOnlyPartition(received)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)))
                .thenReturn(CompletableFuture.completedFuture(fundsReservedPartition(fundsReserved)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve))
                .thenReturn(CompletableFuture.completedFuture(accountAfterReserve));
        when(paymentRepository.reserveFundsTransaction(any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentRepository.completeFundsTransaction(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(buildTransactionConflictCancellation(5)))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.processPayment("pay_17").join();

        verify(paymentRepository, times(1)).reserveFundsTransaction(any(), any(), any(), any(), any(), any());
        verify(paymentRepository, times(2)).completeFundsTransaction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenAuditRowMissing_shouldSkip() {
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenAlreadyConsumed_shouldSkip() {
        Reservation consumed = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.CONSUMED);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(consumed));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenActive_shouldRunReleaseTransact() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository).releaseReservationTransaction(
                eq(active), eq(account), eq(new BigDecimal("100")));
    }

    @Test
    void releaseExpiredReservation_whenReservationConditionFails_shouldSkipQuietly() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        // A concurrent complete already settled the hold: the reservation update (item 0) fails its condition.
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(cancellationFailedAtIndex(0, 2))));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenAlreadyReleased_shouldSkip() {
        // A RELEASED audit row (a prior expiry already restored the hold) must be a no-op, like CONSUMED.
        Reservation released = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.RELEASED);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(released));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenDebtorAccountMissing_shouldSkipQuietly() {
        // The audit row is active but the debtor account row vanished: skip rather than fail the stream record.
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenAccountVersionConflictThenSuccess_shouldRetryAndRelease() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        Account accountAfterDrift = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 3);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account))
                .thenReturn(CompletableFuture.completedFuture(accountAfterDrift));
        // First attempt loses the optimistic-version race on the account item (index 1), second wins.
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(cancellationFailedAtIndex(1, 2))))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, times(2)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(2)).getAccount("acc_usd_1");
    }

    @Test
    void releaseExpiredReservation_whenTransactionConflictThenSuccess_shouldRetryAndRelease() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        // A concurrent transaction touched the same items: TransactionConflict is retried, not skipped.
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(buildTransactionConflictCancellation(2))))
                .thenReturn(CompletableFuture.completedFuture(null));

        processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join();

        verify(paymentRepository, times(2)).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenConflictNeverResolves_shouldExhaustRetriesAndThrow() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(cancellationFailedAtIndex(1, 2))));

        assertThatThrownBy(() -> processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Release transaction failed"));

        // Initial attempt plus the capped release retries (MAX_RELEASE_RETRIES = 3).
        verify(paymentRepository, times(4)).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void releaseExpiredReservation_whenNonConditionalFailure_shouldThrowWithoutRetry() {
        Reservation active = buildAuditReservation("acc_usd_1", "res_pay_x", ReservationStatus.ACTIVE);
        Account account = buildAccount("acc_usd_1", new BigDecimal("10000"), new BigDecimal("9900"), 2);
        when(paymentRepository.getReservation("acc_usd_1", "res_pay_x"))
                .thenReturn(CompletableFuture.completedFuture(active));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("throttle")));

        assertThatThrownBy(() -> processor.releaseExpiredReservation("acc_usd_1", "res_pay_x").join())
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause())
                        .isInstanceOf(RuntimeException.class)
                        .hasMessageContaining("Release transaction failed"));

        // A non-retryable failure propagates immediately without consuming the retry budget.
        verify(paymentRepository, times(1)).releaseReservationTransaction(any(), any(), any());
    }

    /** Builds an audit reservation row in the requested status holding 100 units. */
    private static Reservation buildAuditReservation(String accountId, String reservationId, ReservationStatus status) {
        Reservation reservation = new Reservation();
        reservation.setAccountKey(Account.KEY_PREFIX + accountId);
        reservation.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        reservation.setEntityType(Reservation.ENTITY_TYPE);
        reservation.setReservationId(reservationId);
        reservation.setPaymentId("pay_x");
        reservation.setAmount(new BigDecimal("100"));
        reservation.setStatus(status.name());
        reservation.setCreatedAtUtc(Instant.now());
        return reservation;
    }

    /**
     * Builds a {@link TransactionCanceledException} whose first reason is a {@code TransactionConflict}
     * (a concurrent transaction rather than a precondition failure).
     */
    private static TransactionCanceledException buildTransactionConflictCancellation(int size) {        List<CancellationReason> reasons = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            reasons.add(CancellationReason.builder()
                    .code(i == 0 ? "TransactionConflict" : "None")
                    .build());
        }
        return TransactionCanceledException.builder()
                .cancellationReasons(reasons)
                .message("Transaction is ongoing for the item")
                .build();
    }

    /**
     * Builds a {@link TransactionCanceledException} with a conditional check failure at the given
     * cancellation reason index.
     */
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

    /** Builds a folded {@link Payment} aggregate in the requested state and version. */
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

    /** Partition stub with stream head and a single OUTBOUND_PAYMENT_CREATED event. */
    private PaymentPartitionQueryResult createdOnlyPartition(Payment foldedFromReplay) {
        String paymentId = foldedFromReplay.getPaymentId();
        Instant t = foldedFromReplay.getCreatedAtUtc();
        PaymentStreamHead head = baseHead(paymentId, 1, PaymentState.RECEIVED.name(), t);
        PaymentEvent e1 = createdEvent(foldedFromReplay, t);
        return new PaymentPartitionQueryResult(head, List.of(e1));
    }

    /** Partition stub after funds reservation with matching head and two events. */
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

    /** Partition stub for a completed payment with head and three events. */
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

    /** Partition stub for a rejected payment with head and created plus rejected events. */
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

    /** Builds a stream head aligned with the given sequence, aggregate state, and timestamp. */
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

    /** Builds sequence-1 OUTBOUND_PAYMENT_CREATED event from a folded payment template. */
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

    /** Builds sequence-2 FUNDS_RESERVED event for the given payment and timestamp. */
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

    /** Builds an active USD {@link Account} with the given balances and optimistic version. */
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
