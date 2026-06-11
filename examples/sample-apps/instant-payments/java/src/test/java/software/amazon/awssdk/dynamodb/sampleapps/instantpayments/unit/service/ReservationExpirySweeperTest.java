package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.ReservationExpirySweeper;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Unit tests for {@link ReservationExpirySweeper} (review item C1).
 *
 * <p>Drives {@link ReservationExpirySweeper#sweepOnce()} directly with a mocked repository to verify the
 * release happy path, the skip-when-already-settled path, the retry-on-version-drift path, and the empty sweep.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class ReservationExpirySweeperTest {

    @Mock
    private PaymentRepository paymentRepository;

    private ReservationExpirySweeper sweeper;

    /**
     * Builds the sweeper under test with a mocked repository and a fixed lease window.
     */
    @BeforeEach
    void setUp() {
        sweeper = new ReservationExpirySweeper(paymentRepository, 60_000L);
    }

    @Test
    void sweepOnce_whenExpiredReservation_shouldReleaseWithHeldAmount() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account account = account("acc_usd_1", new BigDecimal("9950"), 4);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(eq(expired), eq(account), eq(new BigDecimal("50"))))
                .thenReturn(CompletableFuture.completedFuture(null));

        sweeper.sweepOnce();

        verify(paymentRepository).releaseReservationTransaction(eq(expired), eq(account), eq(new BigDecimal("50")));
    }

    @Test
    void sweepOnce_whenReservationAlreadySettled_shouldSkipWithoutRetry() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account account = account("acc_usd_1", new BigDecimal("9950"), 4);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        // Reservation update (index 0) failed its condition: a concurrent complete consumed it.
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(failed(conflict("ConditionalCheckFailed", "None")));

        sweeper.sweepOnce();

        verify(paymentRepository, times(1)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(1)).getAccount("acc_usd_1");
    }

    @Test
    void sweepOnce_whenAccountVersionDrift_shouldRetryThenSucceed() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account stale = account("acc_usd_1", new BigDecimal("9950"), 4);
        Account fresh = account("acc_usd_1", new BigDecimal("9950"), 5);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(stale))
                .thenReturn(CompletableFuture.completedFuture(fresh));
        // Account update (index 1) failed its version condition the first time, succeeds after re-read.
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(failed(conflict("None", "ConditionalCheckFailed")))
                .thenReturn(CompletableFuture.completedFuture(null));

        sweeper.sweepOnce();

        verify(paymentRepository, times(2)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(2)).getAccount("acc_usd_1");
    }

    @Test
    void sweepOnce_whenAccountMissing_shouldSkipRelease() {
        Reservation expired = reservation("acc_missing", "res_pay_1", new BigDecimal("50"));

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_missing"))
                .thenReturn(CompletableFuture.completedFuture(null));

        sweeper.sweepOnce();

        verify(paymentRepository).getAccount("acc_missing");
        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    @Test
    void sweepOnce_whenTransactionConflict_shouldRetryThenSucceed() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account stale = account("acc_usd_1", new BigDecimal("9950"), 4);
        Account fresh = account("acc_usd_1", new BigDecimal("9950"), 5);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(stale))
                .thenReturn(CompletableFuture.completedFuture(fresh));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(failed(conflict("TransactionConflict", "None")))
                .thenReturn(CompletableFuture.completedFuture(null));

        sweeper.sweepOnce();

        verify(paymentRepository, times(2)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(2)).getAccount("acc_usd_1");
    }

    @Test
    void sweepOnce_whenRetryableFailurePersists_shouldExhaustRetriesAndReturn() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account account = account("acc_usd_1", new BigDecimal("9950"), 4);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(failed(conflict("TransactionConflict", "None")));

        sweeper.sweepOnce();

        verify(paymentRepository, times(4)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(4)).getAccount("acc_usd_1");
    }

    @Test
    void sweepOnce_whenReleaseFailsNonRetryably_shouldStopAfterFirstAttempt() {
        Reservation expired = reservation("acc_usd_1", "res_pay_1", new BigDecimal("50"));
        Account account = account("acc_usd_1", new BigDecimal("9950"), 4);

        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of(expired)));
        when(paymentRepository.getAccount("acc_usd_1"))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(paymentRepository.releaseReservationTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("boom"))));

        sweeper.sweepOnce();

        verify(paymentRepository, times(1)).releaseReservationTransaction(any(), any(), any());
        verify(paymentRepository, times(1)).getAccount("acc_usd_1");
    }

    @Test
    void sweepOnce_whenNoExpiredReservations_shouldDoNothing() {
        when(paymentRepository.scanExpiredActiveReservations(org.mockito.ArgumentMatchers.anyLong(), eq(50)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        sweeper.sweepOnce();

        verify(paymentRepository, never()).getAccount(any());
        verify(paymentRepository, never()).releaseReservationTransaction(any(), any(), any());
    }

    /**
     * Wraps a cancellation exception in the {@link CompletionException} shape the repository raises.
     *
     * @param ex transaction cancellation to surface
     * @return a failed future carrying the wrapped exception
     */
    private static CompletableFuture<Void> failed(TransactionCanceledException ex) {
        return CompletableFuture.failedFuture(new CompletionException(ex));
    }

    /**
     * Builds a {@link TransactionCanceledException} with per-item cancellation codes.
     *
     * @param reservationItemCode code for the reservation update at transaction index 0
     * @param accountItemCode code for the account update at transaction index 1
     * @return exception describing why the transaction was cancelled
     */
    private static TransactionCanceledException conflict(String reservationItemCode, String accountItemCode) {
        return TransactionCanceledException.builder()
                .cancellationReasons(
                        CancellationReason.builder().code(reservationItemCode).build(),
                        CancellationReason.builder().code(accountItemCode).build())
                .build();
    }

    /**
     * Builds an active reservation that already expired so the sweeper selects it.
     *
     * @param accountId owning account id without the key prefix
     * @param reservationId reservation id without the key prefix
     * @param amount held amount to release
     * @return reservation in {@code ACTIVE} status with a past expiry
     */
    private static Reservation reservation(String accountId, String reservationId, BigDecimal amount) {
        Reservation r = new Reservation();
        r.setAccountKey(Account.KEY_PREFIX + accountId);
        r.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        r.setEntityType(Reservation.ENTITY_TYPE);
        r.setReservationId(reservationId);
        r.setAmount(amount);
        r.setStatus(ReservationStatus.ACTIVE.name());
        r.setCreatedAtUtc(Instant.now());
        r.setExpiresAt(Instant.now().getEpochSecond() - 1);
        return r;
    }

    /**
     * Builds an account row used to back release transactions.
     *
     * @param accountId account id without the key prefix
     * @param availableBalance available balance to expose
     * @param version optimistic lock version the release must match
     * @return account populated with keys, balances, and version
     */
    private static Account account(String accountId, BigDecimal availableBalance, int version) {
        Account a = new Account();
        a.setAccountKey(Account.KEY_PREFIX + accountId);
        a.setEntityKey(Account.KEY_PREFIX + accountId);
        a.setEntityType(Account.ENTITY_TYPE);
        a.setAccountId(accountId);
        a.setAvailableBalance(availableBalance);
        a.setCurrentBalance(new BigDecimal("10000"));
        a.setVersion(version);
        return a;
    }
}
