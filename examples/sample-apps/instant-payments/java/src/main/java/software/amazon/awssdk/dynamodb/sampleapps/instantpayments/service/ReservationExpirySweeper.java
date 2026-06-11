package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Periodically releases expired funds reservations so customer money is never stuck behind a hold whose
 * payment never completed.
 *
 * <p><strong>Why this exists.</strong> A reserve decrements {@link Account#getAvailableBalance()} and writes an
 * {@link ReservationStatus#ACTIVE} {@link Reservation}. If the following complete fails permanently (the
 * processor crashes between reserve and complete, the account version never reconciles, or the streams listener
 * exhausts its poison-pill retries), nothing else would ever return the held funds. The reservation would sit
 * {@code ACTIVE} forever and the available balance would stay decremented.
 *
 * <p><strong>What it does.</strong> Each sweep asks the repository for reservations that are still
 * {@code ACTIVE} but whose {@link Reservation#getExpiresAt()} has passed (a {@code Scan} with a server-side
 * filter), and for each one runs {@link PaymentRepository#releaseReservationTransaction} once: a single
 * {@code TransactWriteItems} that flips the reservation from {@code ACTIVE} to {@link ReservationStatus#RELEASED}
 * and adds the held amount back to {@code availableBalance}. The row is kept (not deleted) so an expired hold
 * stays auditable.
 *
 * <p><strong>Why the scan is not ideal.</strong> A filtered {@code Scan} still reads through the table (or a large
 * portion of it) and then discards non-matching items, so its work grows with overall table size rather than with
 * the number of expiring holds. That is fine for a small sample, but it becomes expensive and slow at scale.
 * Better production options include a queryable expiry index (for example a GSI keyed by expiry time), a
 * time-bucketed or sharded expiration table, or an external scheduler/stream consumer that can locate only the
 * expiring reservations without polling the entire base table.
 *
 * <p><strong>Why it is safe.</strong> The release and a concurrent complete both condition on the same
 * reservation still being {@code ACTIVE}, so exactly one wins and the funds are never double-counted. If a
 * complete wins first, the release sees the reservation already {@link ReservationStatus#CONSUMED} and skips.
 *
 * <p><strong>Retries.</strong> A {@code TransactionConflict} or an account optimistic-version drift is retried
 * up to {@link #MAX_RELEASE_RETRIES} times with a small backoff, re-reading the account each attempt. A
 * conditional failure on the reservation itself means another path already settled it, so the sweeper skips it
 * rather than retrying.
 *
 * <p>Like the streams listener this is a single-threaded {@link SmartLifecycle} component suitable for a sample.
 * It is gated by {@code dynamodb.reservation-sweeper.enabled} (default {@code true}). Integration tests disable
 * it so it does not race their assertions. A production system would run this as a separate scheduled job or
 * Lambda, ideally using an expiry index or other targeted lookup instead of a full-table scan.
 *
 * @apiNote Repository futures are joined on the sweeper's scheduler thread. That is background lifecycle
 * work, not the HTTP request path.
 */
@Component
@ConditionalOnProperty(name = "dynamodb.reservation-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpirySweeper implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    /**
     * Maximum reservations released per sweep. Bounds the work one tick performs. Any remaining expired holds are
     * released on the next sweep.
     */
    private static final int SWEEP_BATCH_LIMIT = 50;

    /**
     * Maximum attempts to release a single reservation when the release is retryable (a {@code TransactionConflict}
     * or an account optimistic-version drift). A conditional failure on the reservation itself is not retried.
     */
    private static final int MAX_RELEASE_RETRIES = 3;

    /** Base backoff between release retries. Attempt {@code n} sleeps {@code n * BASE} milliseconds. */
    private static final long RELEASE_BACKOFF_MILLIS = 20;

    /** Delay before the first sweep so the table, seed data, and other beans are ready. */
    private static final long INITIAL_DELAY_MILLIS = 5_000;

    /** Upper bound, in seconds, that {@link #stop} waits for the sweeper thread to finish its current pass. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    /** Position of the reservation update in {@link PaymentRepository#releaseReservationTransaction}. */
    private static final int RESERVATION_ITEM_INDEX = 0;

    /** Position of the account update in {@link PaymentRepository#releaseReservationTransaction}. */
    private static final int ACCOUNT_ITEM_INDEX = 1;

    /** Persistence and transact operations for reservations and accounts. */
    private final PaymentRepository paymentRepository;

    /** Delay between sweeps, from {@code dynamodb.reservation-sweeper.interval-ms}. */
    private final long sweepIntervalMillis;

    /** Whether the sweeper lifecycle is active. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Single-thread scheduler that drives {@link #sweepLoop}. */
    private ScheduledExecutorService scheduler;

    /**
     * @param paymentRepository    persistence and transact operations for reservations and accounts
     * @param sweepIntervalMillis  delay between sweeps in milliseconds
     */
    public ReservationExpirySweeper(PaymentRepository paymentRepository,
                                    @Value("${dynamodb.reservation-sweeper.interval-ms:60000}") long sweepIntervalMillis) {
        this.paymentRepository = paymentRepository;
        this.sweepIntervalMillis = sweepIntervalMillis;
        logger.info("Reservation expiry sweeper created: sweepIntervalMillis={}", sweepIntervalMillis);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts a daemon single-thread scheduler. The first sweep is delayed so the table and seed data exist.
     */
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting reservation expiry sweeper: sweepIntervalMillis={}", sweepIntervalMillis);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "reservation-sweeper");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(this::sweepLoop, INITIAL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flips {@link #running} to false so the loop reschedules no further work, interrupts the sweeper thread,
     * then waits up to {@link #SHUTDOWN_AWAIT_SECONDS} for the current pass to finish. In-flight calls are bounded
     * by the SDK {@code apiCallTimeout}, so the wait drains well inside the configured shutdown window.
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping reservation expiry sweeper");
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    if (!scheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                        logger.warn("Reservation expiry sweeper did not finish within {}s of shutdown, "
                                + "proceeding with context close", SHUTDOWN_AWAIT_SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Runs after most other beans so the repository and table initializer are ready before the first sweep.
     *
     * @return {@link Integer#MAX_VALUE} so start happens late in the context lifecycle
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Runs one sweep, then reschedules itself at {@link #sweepIntervalMillis}. */
    private void sweepLoop() {
        try {
            sweepOnce();
        } catch (Exception e) {
            logger.error("Reservation expiry sweep error", e);
        }
        if (running.get() && scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::sweepLoop, sweepIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Loads up to {@link #SWEEP_BATCH_LIMIT} expired {@code ACTIVE} reservations and releases each one.
     *
     * <p>Public so it can be driven directly (for example from a test or an operational trigger). The batch is
     * bounded by {@link #SWEEP_BATCH_LIMIT}, so a full pass is short and graceful shutdown only needs to interrupt
     * the scheduler between passes.
     */
    public void sweepOnce() {
        long nowEpochSecond = Instant.now().getEpochSecond();
        List<Reservation> expired = paymentRepository
                .scanExpiredActiveReservations(nowEpochSecond, SWEEP_BATCH_LIMIT)
                .join();
        if (expired.isEmpty()) {
            return;
        }
        logger.debug("Releasing expired reservations: count={}", expired.size());
        for (Reservation reservation : expired) {
            releaseReservation(reservation);
        }
    }

    /**
     * Releases a single expired hold, retrying retryable failures up to {@link #MAX_RELEASE_RETRIES} times.
     *
     * @param reservation expired {@code ACTIVE} reservation to release
     */
    private void releaseReservation(Reservation reservation) {
        String accountId = accountIdFromKey(reservation.getAccountKey());

        int attempt = 0;
        while (true) {
            Account account = paymentRepository.getAccount(accountId).join();
            if (account == null) {
                logger.warn("Cannot release expired reservation, debtor account missing: reservationKey={}, accountId={}",
                        reservation.getReservationKey(), accountId);
                return;
            }

            try {
                paymentRepository.releaseReservationTransaction(reservation, account, reservation.getAmount()).join();
                logger.debug("Released expired reservation: reservationKey={}, accountId={}, amount={}",
                        reservation.getReservationKey(), accountId, reservation.getAmount());
                return;
            } catch (CompletionException e) {
                if (isItemConditionalFailure(e, RESERVATION_ITEM_INDEX)) {
                    logger.debug("Expired reservation already settled, skipping release: reservationKey={}",
                            reservation.getReservationKey());
                    return;
                }
                boolean retryable = isItemConditionalFailure(e, ACCOUNT_ITEM_INDEX) || isTransactionConflict(e);
                if (!retryable) {
                    logger.error("Failed to release expired reservation: reservationKey={}",
                            reservation.getReservationKey(), e);
                    return;
                }
                attempt++;
                if (attempt > MAX_RELEASE_RETRIES) {
                    logger.warn("Exhausted retries releasing expired reservation, will retry on a later sweep: "
                                    + "reservationKey={}, attempts={}",
                            reservation.getReservationKey(), attempt);
                    return;
                }
                backoff(attempt);
            }
        }
    }

    /**
     * Strips the {@link Account#KEY_PREFIX} from a reservation's partition key to recover the business account id.
     *
     * @param accountKey reservation partition key of the form {@code ACCOUNT#}{@code accountId}
     * @return the business account id
     */
    private static String accountIdFromKey(String accountKey) {
        return accountKey.startsWith(Account.KEY_PREFIX)
                ? accountKey.substring(Account.KEY_PREFIX.length())
                : accountKey;
    }

    /**
     * @param e     wrapper from {@code join()} on the release transact
     * @param index transact item position to inspect (see {@link #RESERVATION_ITEM_INDEX}, {@link #ACCOUNT_ITEM_INDEX})
     * @return {@code true} if the cancellation reason at {@code index} is a conditional check failure
     */
    private static boolean isItemConditionalFailure(CompletionException e, int index) {
        Throwable cause = e.getCause();
        if (!(cause instanceof TransactionCanceledException tce)) {
            return false;
        }
        List<CancellationReason> reasons = tce.cancellationReasons();
        return reasons.size() > index && "ConditionalCheckFailed".equals(reasons.get(index).code());
    }

    /**
     * @return {@code true} if the transact was cancelled by a concurrent {@code TransactionConflict}, which the SDK
     *     retry strategy does not retry
     */
    private static boolean isTransactionConflict(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof TransactionCanceledException tce
                && tce.cancellationReasons().stream()
                .anyMatch(r -> "TransactionConflict".equals(r.code()));
    }

    /**
     * Sleeps a short, attempt-scaled interval between release retries.
     *
     * @param attempt 1-based retry number
     */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(RELEASE_BACKOFF_MILLIS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during reservation release backoff", e);
        }
    }
}
