package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.TransactionConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.*;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.AsyncSupport;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Processes an outbound payment through the full lifecycle:
 * validate, reserve funds, then complete or reject.
 *
 * <p>Triggered by DynamoDB Streams ({@code INSERT} of the first payment event) or
 * {@code POST /{paymentId}/process}. Idempotency relies on conditional stream-head updates and
 * state checks, not on exactly-once delivery.
 *
 * <p><strong>Concurrency:</strong> Multiple invocations for the same {@code paymentId} are safe:
 * {@link PaymentRepository}
 * uses {@code TransactWriteItems} with optimistic conditions on the stream head, account
 * balances/versions, and reservation state. Losers receive {@code ConditionalCheckFailed} and this
 * class reconciles via {@link #handleReserveConflict(String)} or logs-and-skips on complete/reject.
 *
 * <p>A {@code TransactionConflict} cancellation is different: it is not a precondition failure but a
 * concurrent transaction touching the same item. The SDK retry strategy does not retry it, so
 * {@link #processPayment(String)} retries the whole flow in-call up to
 * {@link #MAX_TRANSACTION_CONFLICT_RETRIES} times with a small backoff. Each retry re-loads the head
 * and rebuilds the transact, so two processors racing on the same payment converge within the same
 * call instead of relying on the next stream delivery.
 *
 * @apiNote The folded aggregate is always derived from stored events plus an invariant check against
 *     {@link PaymentStreamHead}. Callers never pass ad-hoc state that could diverge from DynamoDB.
 *
 * <p>Returns {@link CompletableFuture} so async MVC controllers and the streams listener can compose
 * without blocking Tomcat worker threads.
 */
@Service
public class OutboundPaymentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboundPaymentProcessor.class);

    /**
     * Maximum number of in-call retries when a transact is canceled with {@code TransactionConflict}
     * (a concurrent transaction, not a precondition failure). The SDK retry strategy does not retry this
     * reason, so the processor caps it here to bound the work a single racing call performs.
     */
    public static final int MAX_TRANSACTION_CONFLICT_RETRIES = 3;

    /**
     * Base backoff between {@code TransactionConflict} retries. Attempt {@code n} sleeps
     * {@code n * BASE} milliseconds so racing callers stagger and one wins quickly.
     */
    private static final long TRANSACTION_CONFLICT_BACKOFF_MILLIS = 20;

    /**
     * Maximum attempts to read the payment partition when the stream head and the replayed events are
     * momentarily inconsistent. The head and the events are separate items, so a concurrent transact
     * that advances the head and appends its event can be observed half-applied. Re-reading resolves
     * the skew, so this is a transient-read bound, not a precondition retry.
     */
    private static final int PARTITION_READ_MAX_ATTEMPTS = 5;

    /** Base backoff between payment-partition re-reads. Attempt {@code n} waits {@code n * BASE} ms. */
    private static final long PARTITION_READ_BACKOFF_MILLIS = 10;

    /**
     * Maximum number of in-call attempts to run the release transact for one expired hold.
     *
     * <p>Only retryable failures count against this budget: a {@code TransactionConflict} (a concurrent
     * transaction touched the same item) or a conditional failure on the account update (optimistic-version
     * drift, the account moved between the read and the transact). A conditional failure on the reservation
     * item is not retryable, since it means another path already consumed or released the same hold, so the
     * release is skipped instead of retried. Once this cap is reached the failure propagates to the streams
     * listener, which applies its own record-level retry.
     */
    private static final int MAX_RELEASE_RETRIES = 3;

    /**
     * Base backoff in milliseconds between release-transact retries. Attempt {@code n} (one-based) sleeps
     * {@code n * RELEASE_BACKOFF_MILLIS} before retrying, so racing callers stagger and one wins quickly.
     */
    private static final long RELEASE_BACKOFF_MILLIS = 20;

    /**
     * Transact item position of the reservation update in {@link PaymentRepository#releaseReservationTransaction}.
     *
     * <p>Used to read the per-item cancellation reason: a conditional failure at this index means the hold was
     * already settled, so {@link #recoverReleaseFailure} skips rather than retries.
     */
    private static final int RELEASE_RESERVATION_ITEM_INDEX = 0;

    /**
     * Transact item position of the account update in {@link PaymentRepository#releaseReservationTransaction}.
     *
     * <p>Used to read the per-item cancellation reason: a conditional failure at this index is an
     * optimistic-version drift on the account, which {@link #recoverReleaseFailure} treats as retryable.
     */
    private static final int RELEASE_ACCOUNT_ITEM_INDEX = 1;

    /**
     * Transact item position of the reservation update in {@link PaymentRepository#completeFundsTransaction}.
     *
     * <p>Both repository implementations build the complete transact with the reservation {@code CONSUMED} update at
     * this index. A conditional failure here, when the stream head item did not also fail, means the hold expired
     * ({@code expiresAt <= now}) or was already released, so {@link #recoverCompleteFailure} rejects the payment.
     */
    private static final int COMPLETE_RESERVATION_ITEM_INDEX = 1;

    /**
     * Transact item position of the stream head update in {@link PaymentRepository#completeFundsTransaction}.
     *
     * <p>A conditional failure here means another processor already advanced the payment past
     * {@link PaymentState#FUNDS_RESERVED}, so {@link #recoverCompleteFailure} treats it as an idempotent no-op rather
     * than an expiry.
     */
    private static final int COMPLETE_STREAM_HEAD_ITEM_INDEX = 4;

    /**
     * Reason code recorded on the {@link PaymentEventType#REJECTED} event when a completion arrives after the hold
     * deadline. The deadline guard in {@link PaymentRepository#completeFundsTransaction} fails its condition, and the
     * payment is rejected instead of settling a stale hold.
     */
    private static final String RESERVATION_EXPIRED_REASON = "RESERVATION_EXPIRED";

    /** Persistence and transact operations for payments and accounts. */
    private final PaymentRepository paymentRepository;
    /** Folds event streams into {@link Payment} aggregates. */
    private final PaymentEventReplayer paymentEventReplayer;
    /** Async delay and composition helpers for retry backoffs. */
    private final AsyncSupport asyncSupport;
    /**
     * Seconds a reservation hold stays valid. A new hold's temporary reservation gets {@code ttl} set to
     * {@code createdAt + this}, after which DynamoDB deletes the temporary reservation and the streams listener releases the
     * hold. From {@code dynamodb.reservation-timeout-seconds}.
     */
    private final long reservationTimeoutSeconds;

    /**
     * @param paymentRepository   persistence and transact operations for payments and accounts
     * @param paymentEventReplayer  folds event streams into {@link Payment} aggregates
     * @param asyncSupport        delay and async composition helpers for in-call retries
     * @param reservationTimeoutSeconds  hold lifetime in seconds, used to stamp {@code ttl} on the temporary reservation
     */
    public OutboundPaymentProcessor(PaymentRepository paymentRepository,
                                    PaymentEventReplayer paymentEventReplayer,
                                    AsyncSupport asyncSupport,
                                    @Value("${dynamodb.reservation-timeout-seconds:900}") long reservationTimeoutSeconds) {
        this.paymentRepository = paymentRepository;
        this.paymentEventReplayer = paymentEventReplayer;
        this.asyncSupport = asyncSupport;
        this.reservationTimeoutSeconds = reservationTimeoutSeconds;
    }

    /**
     * Processes a payment through validation, fund reservation, and completion/rejection.
     *
     * <p>Routing is by current aggregate state after replay: {@link PaymentState#RECEIVED} runs
     * validation and reserve (then complete in the same call if reserve succeeds).
     * {@link PaymentState#FUNDS_RESERVED} only runs completion (e.g. reserve succeeded elsewhere
     * or a prior call stopped after reserve). Terminal states short-circuit.
     *
     * <p>If a transact is canceled with {@code TransactionConflict} (a concurrent transaction rather
     * than a precondition failure), the whole flow is retried in-call up to
     * {@link #MAX_TRANSACTION_CONFLICT_RETRIES} times with a small backoff. Each retry re-loads the head
     * and rebuilds the transact, so racing processors converge here instead of waiting for stream
     * re-delivery. Retries are exhausted into a {@link RuntimeException}.
     *
     * @param paymentId logical payment identifier
     * @return completed void future when processing finishes or short-circuits on a terminal state
     */
    public CompletableFuture<Void> processPayment(String paymentId) {
        return processPaymentWithConflictRetry(paymentId, 0);
    }

    /**
     * Releases an expired hold after its temporary reservation was deleted by DynamoDB.
     *
     * <p>Triggered by the streams listener on the {@code REMOVE} of a {@code RESERVATION_TEMP#} temporary reservation row.
     * Loads the audit reservation and short circuits when it is missing or no longer {@link ReservationStatus#ACTIVE}
     * (the payment already completed or the hold was already released), so a temporary reservation that expires after a normal
     * completion does no work. When the hold is still {@code ACTIVE} it runs
     * {@link PaymentRepository#releaseReservationTransaction} to restore {@code availableBalance} and mark the
     * audit row {@link ReservationStatus#RELEASED}, retrying retryable conflicts up to {@link #MAX_RELEASE_RETRIES}
     * times.
     *
     * @param accountId     debtor account id parsed from the temporary reservation partition key
     * @param reservationId reservation id parsed from the temporary reservation sort key
     * @return void future when the release finishes or is correctly skipped
     */
    public CompletableFuture<Void> releaseExpiredReservation(String accountId, String reservationId) {
        return paymentRepository.getReservation(accountId, reservationId)
                .thenCompose(reservation -> {
                    if (reservation == null) {
                        logger.debug("Expired hold has no audit reservation, skipping release: accountId={}, reservationId={}",
                                accountId, reservationId);
                        return CompletableFuture.completedFuture(null);
                    }
                    if (!ReservationStatus.ACTIVE.name().equals(reservation.getStatus())) {
                        logger.debug("Expired reservation already settled, skipping release: accountId={}, reservationId={}, status={}",
                                accountId, reservationId, reservation.getStatus());
                        return CompletableFuture.completedFuture(null);
                    }
                    return releaseActiveReservationWithRetry(accountId, reservation, 0);
                });
    }

    /**
     * Runs the release transact for an active hold and retries retryable failures with a small backoff.
     *
     * @param accountId   debtor account id used to re-read the account before each attempt
     * @param reservation audit reservation row supplying keys and held amount
     * @param attempt     zero-based retry count for backoff and cap checks
     * @return void future when the release finishes or is correctly skipped
     */
    private CompletableFuture<Void> releaseActiveReservationWithRetry(String accountId,
                                                                      Reservation reservation,
                                                                      int attempt) {
        return paymentRepository.getAccount(accountId)
                .thenCompose(account -> {
                    if (account == null) {
                        logger.warn("Cannot release expired reservation, debtor account missing: accountId={}, reservationId={}",
                                accountId, reservation.getReservationId());
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return AsyncSupport.exceptionallyCompose(
                            paymentRepository.releaseReservationTransaction(reservation, account, reservation.getAmount())
                                    .thenApply(ignored -> {
                                        logger.debug("Released expired reservation: accountId={}, reservationId={}, amount={}",
                                                accountId, reservation.getReservationId(), reservation.getAmount());
                                        return (Void) null;
                                    }),
                            error -> recoverReleaseFailure(accountId, reservation, attempt, error));
                });
    }

    /**
     * Maps release transact failures to a skip, an in-call retry, or a propagated error.
     *
     * <p>A conditional failure on the reservation item means another path already settled the hold, so the
     * release is skipped. A conditional failure on the account (optimistic-version drift) or a
     * {@code TransactionConflict} is retried up to {@link #MAX_RELEASE_RETRIES} times. Any other failure, or an
     * exhausted retry budget, propagates so the streams listener can apply its own record-level retry.
     *
     * @param accountId   debtor account id used to re-read the account on retry
     * @param reservation audit reservation row being released
     * @param attempt     zero-based retry count that produced this failure
     * @param error       failure from the release future chain
     * @return void future that succeeds on skip, retries, or fails to signal the listener
     */
    private CompletableFuture<Void> recoverReleaseFailure(String accountId,
                                                          Reservation reservation,
                                                          int attempt,
                                                          Throwable error) {
        CompletionException completionException = toCompletionException(error);
        if (isItemConditionalFailure(completionException, RELEASE_RESERVATION_ITEM_INDEX)) {
            logger.debug("Expired reservation already settled, skipping release: accountId={}, reservationId={}",
                    accountId, reservation.getReservationId());
            return CompletableFuture.completedFuture(null);
        }
        boolean retryable = isItemConditionalFailure(completionException, RELEASE_ACCOUNT_ITEM_INDEX)
                || isTransactionConflict(completionException);
        if (!retryable || attempt + 1 > MAX_RELEASE_RETRIES) {
            return CompletableFuture.failedFuture(new RuntimeException(
                    "Release transaction failed for reservation " + reservation.getReservationId(),
                    unwrap(completionException)));
        }
        logger.debug("Release transaction conflict, retrying in-call: accountId={}, reservationId={}, attempt={}",
                accountId, reservation.getReservationId(), attempt + 1);
        return asyncSupport.sleepMillis(RELEASE_BACKOFF_MILLIS * (attempt + 1))
                .thenCompose(ignored -> releaseActiveReservationWithRetry(accountId, reservation, attempt + 1));
    }

    /**
     * Returns the current folded payment (replay of the event stream).
     *
     * @param paymentId logical payment identifier
     * @return folded payment future
     */
    public CompletableFuture<Payment> getPayment(String paymentId) {
        return loadFolded(paymentId).thenApply(LoadedPayment::folded);
    }

    /**
     * Runs {@link #processPaymentOnce(String)} and retries the whole flow on {@link TransactionConflictException}.
     *
     * @param paymentId       payment being processed
     * @param conflictAttempt zero-based attempt count for backoff and cap checks
     * @return void future when processing finishes or fails with a non-retryable error
     */
    private CompletableFuture<Void> processPaymentWithConflictRetry(String paymentId, int conflictAttempt) {
        return processPaymentOnce(paymentId)
                .handle((ignored, error) -> {
                    if (error == null) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    Throwable cause = AsyncSupport.unwrap(error);
                    if (cause instanceof TransactionConflictException transactionConflict) {
                        if (conflictAttempt >= MAX_TRANSACTION_CONFLICT_RETRIES) {
                            return CompletableFuture.<Void>failedFuture(new RuntimeException(
                                    "Transaction conflict retries exhausted for payment " + paymentId,
                                    transactionConflict.getCause()));
                        }
                        logger.debug("Transaction conflict, retrying payment in-call: paymentId={}, attempt={}",
                                paymentId, conflictAttempt + 1);
                        return asyncSupport.sleepMillis(TRANSACTION_CONFLICT_BACKOFF_MILLIS * (conflictAttempt + 1))
                                .thenCompose(ignoredDelay -> processPaymentWithConflictRetry(paymentId, conflictAttempt + 1));
                    }
                    if (error instanceof CompletionException completionException) {
                        return CompletableFuture.<Void>failedFuture(completionException);
                    }
                    return CompletableFuture.<Void>failedFuture(error);
                })
                .thenCompose(future -> future);
    }

    /**
     * Runs one pass of the payment flow. Routes by replayed state and may complete exceptionally with
     * {@link TransactionConflictException} so {@link #processPayment(String)} can retry.
     *
     * @param paymentId logical payment identifier
     * @return void future when the pass completes
     */
    private CompletableFuture<Void> processPaymentOnce(String paymentId) {
        return loadFolded(paymentId)
                .thenCompose(loaded -> {
                    Payment payment = loaded.folded();
                    String state = payment.getState();
                    String correlationId = payment.getCorrelationId();

                    if (isTerminal(state)) {
                        logger.debug("Outbound payment already terminal, skipping processing: paymentId={}, state={}",
                                paymentId, state);
                        return CompletableFuture.completedFuture(null);
                    }

                    if (PaymentState.RECEIVED.name().equals(state)) {
                        return processFromReceived(payment, loaded.head(), correlationId);
                    }
                    if (PaymentState.FUNDS_RESERVED.name().equals(state)) {
                        return completeFromFundsReserved(paymentId, correlationId);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Continues processing from {@link PaymentState#RECEIVED}: validates account and balance, reserves funds, then completes.
     *
     * @param payment        aggregate in RECEIVED state
     * @param head           current stream head for conditional updates
     * @param correlationId  tracing id propagated to events
     * @return void future when the branch completes
     */
    private CompletableFuture<Void> processFromReceived(Payment payment, PaymentStreamHead head, String correlationId) {
        return paymentRepository.getAccount(payment.getDebtorAccountId())
                .thenCompose(account -> {
                    if (account == null) {
                        return rejectPayment(payment.getPaymentId(), correlationId, "ACCOUNT_NOT_FOUND");
                    }
                    if (account.getAvailableBalance().compareTo(payment.getAmount()) < 0) {
                        return rejectPayment(payment.getPaymentId(), correlationId, "INSUFFICIENT_FUNDS");
                    }
                    return reserveFunds(payment, head, account, correlationId)
                            .thenCompose(reservedHead -> {
                                if (reservedHead == null) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                return paymentRepository.getAccount(payment.getDebtorAccountId())
                                        .thenCompose(refreshedAccount -> completePayment(
                                                payment, reservedHead, refreshedAccount, correlationId));
                            });
                });
    }

    /**
     * Completes a payment already in {@link PaymentState#FUNDS_RESERVED} (e.g. after a prior reserve-only step).
     *
     * @param paymentId      payment identifier
     * @param correlationId  tracing id for completion event
     * @return void future when completion finishes
     */
    private CompletableFuture<Void> completeFromFundsReserved(String paymentId, String correlationId) {
        return loadFolded(paymentId)
                .thenCompose(loaded -> paymentRepository.getAccount(loaded.folded().getDebtorAccountId())
                        .thenCompose(account -> completePayment(paymentId, account, correlationId)));
    }

    /**
     * Runs the reserve transact: stream head, account balances, reservation row, and {@link PaymentEventType#FUNDS_RESERVED} event.
     *
     * @return future with the post-reserve stream head, or {@code null} when a conditional conflict was reconciled
     */
    private CompletableFuture<PaymentStreamHead> reserveFunds(Payment payment,
                                                              PaymentStreamHead head,
                                                              Account account,
                                                              String correlationId) {
        Instant now = Instant.now();
        Reservation reservation = buildReservation(payment, account, now);
        Reservation temporaryReservation = buildTemporaryReservation(payment, account, now);
        long nextSeq = head.getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.FUNDS_RESERVED, nextSeq, correlationId, null);

        return AsyncSupport.exceptionallyCompose(
                paymentRepository.reserveFundsTransaction(head, account, reservation, temporaryReservation, event, payment.getAmount())
                        .thenApply(ignored -> {
                            logger.debug("Funds reserved for outbound payment: paymentId={}", payment.getPaymentId());
                            return advanceHeadToReserved(head, payment);
                        }),
                error -> recoverReserveFailure(payment.getPaymentId(), error));
    }

    /**
     * Maps reserve transact failures to reconciliation, {@link TransactionConflictException}, or a wrapped error.
     *
     * @param paymentId payment that failed to reserve
     * @param error     failure from the reserve future chain
     * @return reconciled head on conditional conflict, otherwise a failed future
     */
    private CompletableFuture<PaymentStreamHead> recoverReserveFailure(String paymentId, Throwable error) {
        CompletionException completionException = toCompletionException(error);
        if (isConditionalCheckFailed(completionException)) {
            return handleReserveConflict(paymentId).thenApply(ignored -> null);
        }
        if (isTransactionConflict(completionException)) {
            return CompletableFuture.failedFuture(
                    new TransactionConflictException(unwrap(completionException)));
        }
        return CompletableFuture.failedFuture(new RuntimeException(
                "Reserve transaction failed for payment " + paymentId,
                unwrap(completionException)));
    }

    /**
     * Handles {@link TransactionCanceledException} with conditional failure from
     * {@link PaymentRepository#reserveFundsTransaction}.
     *
     * @param paymentId payment being processed
     * @return void future when reconciliation completes
     */
    private CompletableFuture<Void> handleReserveConflict(String paymentId) {
        return loadFolded(paymentId)
                .thenCompose(loaded -> {
                    Payment fresh = loaded.folded();
                    String state = fresh.getState();

                    if (isTerminal(state)) {
                        logger.debug("Outbound payment already processed after reserve conflict, skipping: paymentId={}, state={}",
                                paymentId, state);
                        return CompletableFuture.completedFuture(null);
                    }

                    if (PaymentState.FUNDS_RESERVED.name().equals(state)) {
                        return paymentRepository.getAccount(fresh.getDebtorAccountId())
                                .thenCompose(account -> completePayment(paymentId, account, fresh.getCorrelationId()));
                    }

                    if (PaymentState.RECEIVED.name().equals(state)) {
                        logger.debug(
                                "Outbound payment still in RECEIVED after reserve conflict; skipping until a later retry: "
                                        + "paymentId={}, state={}",
                                paymentId, state);
                        return CompletableFuture.completedFuture(null);
                    }

                    logger.warn("Outbound payment in unexpected non-terminal state after reserve conflict: paymentId={}, state={}",
                            paymentId, state);
                    return CompletableFuture.completedFuture(null);
                });
    }

    /**
     * Finalizes a payment after reloading the partition.
     *
     * @param paymentId      payment to complete
     * @param account        debtor account (fresh read for balances and version)
     * @param correlationId  tracing id on the completion event
     * @return void future when completion finishes
     */
    private CompletableFuture<Void> completePayment(String paymentId, Account account, String correlationId) {
        return loadFolded(paymentId)
                .thenCompose(loaded -> completePayment(loaded.folded(), loaded.head(), account, correlationId));
    }

    /**
     * Finalizes the payment: ledger debit, reservation consumed, {@link PaymentEventType#COMPLETED}, conditional on head sequence.
     *
     * @param payment        folded aggregate supplying ledger and reservation reference fields
     * @param head           stream head supplying the expected sequence for the conditional update
     * @param account        debtor account (fresh read for balances and version)
     * @param correlationId  tracing id on the completion event
     * @return void future when completion finishes
     */
    private CompletableFuture<Void> completePayment(Payment payment,
                                                    PaymentStreamHead head,
                                                    Account account,
                                                    String correlationId) {
        String paymentId = payment.getPaymentId();
        Reservation reservationRef = buildReservationRef(payment, account);
        Reservation temporaryReservationRef = buildTemporaryReservationRef(payment, account);
        LedgerEntry ledgerEntry = buildLedgerEntry(payment, account);
        long nextSeq = head.getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.COMPLETED, nextSeq, correlationId, null);

        return AsyncSupport.exceptionallyCompose(
                paymentRepository.completeFundsTransaction(
                                head, account, reservationRef, temporaryReservationRef, ledgerEntry, event, payment.getAmount())
                        .thenApply(ignored -> {
                            logger.debug("Outbound payment completed: paymentId={}", paymentId);
                            return (Void) null;
                        }),
                error -> recoverCompleteFailure(paymentId, correlationId, error));
    }

    /**
     * Maps complete transact failures to a no-op, an expiry rejection, or a propagated error.
     *
     * <p>The complete transact carries the reservation {@code CONSUMED} update at
     * {@link #COMPLETE_RESERVATION_ITEM_INDEX} (conditional on {@code status = ACTIVE AND expiresAt > now}) and the
     * stream head transition at {@link #COMPLETE_STREAM_HEAD_ITEM_INDEX}. The per-item cancellation reasons tell the
     * two failure modes apart without a re-read:
     * <ul>
     *   <li>Stream head item failed: another processor already advanced the payment past
     *       {@link PaymentState#FUNDS_RESERVED}, so this is an idempotent no-op.</li>
     *   <li>Reservation item failed while the head did not: the hold expired or was already released, so the payment
     *       is rejected with {@link #RESERVATION_EXPIRED_REASON}. The still-pending temporary reservation restores the
     *       held funds later when its {@code REMOVE} drives {@link #releaseExpiredReservation(String, String)}.</li>
     * </ul>
     *
     * <p>Any other conditional failure (for example the ledger write-once guard when a prior completion already wrote
     * the ledger line) is treated as an idempotent no-op. A {@code TransactionConflict} is surfaced for in-call retry,
     * and anything else is wrapped and propagated.
     *
     * @param paymentId     payment that failed to complete
     * @param correlationId tracing id propagated to a rejection event when the hold expired
     * @param error         failure from the complete future chain
     * @return void future that succeeds on no-op, completes the rejection, or fails on an unexpected error
     */
    private CompletableFuture<Void> recoverCompleteFailure(String paymentId, String correlationId, Throwable error) {
        CompletionException completionException = toCompletionException(error);
        if (isItemConditionalFailure(completionException, COMPLETE_STREAM_HEAD_ITEM_INDEX)) {
            logger.debug("Outbound payment already completed by another processor: paymentId={}", paymentId);
            return CompletableFuture.completedFuture(null);
        }
        if (isItemConditionalFailure(completionException, COMPLETE_RESERVATION_ITEM_INDEX)) {
            logger.warn("Hold expired before completion, rejecting outbound payment: paymentId={}, reasonCode={}",
                    paymentId, RESERVATION_EXPIRED_REASON);
            return rejectPayment(paymentId, correlationId, RESERVATION_EXPIRED_REASON);
        }
        if (isConditionalCheckFailed(completionException)) {
            logger.debug("Outbound payment already completed by another processor: paymentId={}", paymentId);
            return CompletableFuture.completedFuture(null);
        }
        if (isTransactionConflict(completionException)) {
            return CompletableFuture.failedFuture(
                    new TransactionConflictException(unwrap(completionException)));
        }
        return CompletableFuture.failedFuture(new RuntimeException(
                "Complete transaction failed for payment " + paymentId,
                unwrap(completionException)));
    }

    /**
     * Maps reject transact failures to a no-op on conditional conflict or a propagated error.
     *
     * @param paymentId payment that failed to reject
     * @param error     failure from the reject future chain
     * @return void future that succeeds when the payment is already terminal
     */
    private CompletableFuture<Void> recoverRejectFailure(String paymentId, Throwable error) {
        CompletionException completionException = toCompletionException(error);
        if (isConditionalCheckFailed(completionException)) {
            logger.debug("Outbound payment already terminal, skipping rejection: paymentId={}", paymentId);
            return CompletableFuture.completedFuture(null);
        }
        if (isTransactionConflict(completionException)) {
            return CompletableFuture.failedFuture(
                    new TransactionConflictException(unwrap(completionException)));
        }
        return CompletableFuture.failedFuture(new RuntimeException(
                "Reject transaction failed for payment " + paymentId,
                unwrap(completionException)));
    }

    /**
     * Appends {@link PaymentEventType#REJECTED} and updates the stream head when preconditions allow.
     *
     * @param reasonCode machine-oriented reason (e.g. {@code INSUFFICIENT_FUNDS})
     * @return void future when rejection finishes
     */
    private CompletableFuture<Void> rejectPayment(String paymentId, String correlationId, String reasonCode) {
        return loadFolded(paymentId)
                .thenCompose(loaded -> {
                    Payment payment = loaded.folded();
                    long nextSeq = loaded.head().getLastSequence() + 1;
                    PaymentEvent event = buildTransitionEvent(
                            payment, PaymentEventType.REJECTED, nextSeq, correlationId, reasonCode);

                    return AsyncSupport.exceptionallyCompose(
                            paymentRepository.rejectPaymentTransaction(loaded.head(), event, reasonCode)
                                    .thenApply(ignored -> {
                                        logger.debug("Outbound payment rejected: paymentId={}, reasonCode={}",
                                                paymentId, reasonCode);
                                        return (Void) null;
                                    }),
                            error -> recoverRejectFailure(paymentId, error));
                });
    }

    /**
     * Loads the payment partition, replays events, and checks head vs folded aggregate consistency.
     *
     * @throws PaymentNotFoundException if the partition is absent
     */
    private CompletableFuture<LoadedPayment> loadFolded(String paymentId) {
        return loadFoldedAttempt(paymentId, 1);
    }

    /**
     * Single attempt to load and fold a payment partition, with bounded backoff when head and replay disagree.
     *
     * @param paymentId payment partition to read
     * @param attempt   one-based read attempt used for backoff before retry
     * @return loaded head and folded aggregate when consistent
     */
    private CompletableFuture<LoadedPayment> loadFoldedAttempt(String paymentId, int attempt) {
        return paymentRepository.queryPaymentPartition(paymentId)
                .thenCompose(partition -> {
                    if (partition == null) {
                        return CompletableFuture.failedFuture(new PaymentNotFoundException(paymentId));
                    }
                    Payment folded = paymentEventReplayer.fold(paymentId, partition.events());
                    if (headMatchesFold(partition.streamHead(), folded)) {
                        return CompletableFuture.completedFuture(
                                new LoadedPayment(partition.streamHead(), folded));
                    }
                    if (attempt >= PARTITION_READ_MAX_ATTEMPTS) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Stream head does not match replayed aggregate for payment " + paymentId));
                    }
                    return asyncSupport.sleepMillis(PARTITION_READ_BACKOFF_MILLIS * attempt)
                            .thenCompose(ignored -> loadFoldedAttempt(paymentId, attempt + 1));
                });
    }

    /**
     * Builds the audit {@link Reservation} item for the reserve transact.
     *
     * <p>The audit row carries the lifecycle {@code status} and leaves {@code ttl} null so DynamoDB never
     * deletes it. The hold stays auditable after it is consumed or released. It still carries {@code expiresAt}
     * (the same epoch-second deadline as the matching temporary reservation) so the complete transaction can reject a
     * settlement that arrives after the deadline.
     *
     * @param payment payment that owns the hold
     * @param account debtor account supplying the partition key
     * @param now     creation instant shared with the matching temporary reservation
     * @return the audit reservation item
     */
    private Reservation buildReservation(Payment payment, Account account, Instant now) {
        String reservationId = deriveReservationId(payment.getPaymentId());

        Reservation reservation = new Reservation();
        reservation.setAccountKey(account.getAccountKey());
        reservation.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        reservation.setEntityType(Reservation.ENTITY_TYPE);
        reservation.setReservationId(reservationId);
        reservation.setPaymentId(payment.getPaymentId());
        reservation.setAmount(payment.getAmount());
        reservation.setStatus(ReservationStatus.ACTIVE.name());
        reservation.setCreatedAtUtc(now);
        reservation.setExpiresAtEpochSecond(holdDeadlineEpochSecond(now));
        return reservation;
    }

    /**
     * Builds the temporary reservation {@link Reservation} item written alongside the audit row.
     *
     * <p>The temporary reservation is a minimal timer: it stores only the keys, its {@code entityType} discriminator, the
     * table {@code ttl} attribute set to {@code now + reservationTimeoutSeconds}, and the same value under
     * {@code expiresAt}. The release path never reads the temporary reservation's own attributes (a {@code NEW_IMAGE}
     * stream {@code REMOVE} carries only the keys), so the held amount and status live on the audit row instead.
     * DynamoDB deletes the temporary reservation once the deadline passes, and the resulting stream {@code REMOVE} record
     * drives {@link #releaseExpiredReservation(String, String)}.
     *
     * @param payment payment that owns the hold
     * @param account debtor account supplying the partition key
     * @param now     creation instant used to derive the {@code ttl} deadline
     * @return the temporary reservation item
     */
    private Reservation buildTemporaryReservation(Payment payment, Account account, Instant now) {
        String reservationId = deriveReservationId(payment.getPaymentId());

        Reservation temporaryReservation = new Reservation();
        temporaryReservation.setAccountKey(account.getAccountKey());
        temporaryReservation.setReservationKey(Reservation.TEMPORARY_KEY_PREFIX + reservationId);
        temporaryReservation.setEntityType(Reservation.TEMPORARY_ENTITY_TYPE);
        temporaryReservation.setTtl(holdDeadlineEpochSecond(now));
        temporaryReservation.setExpiresAtEpochSecond(holdDeadlineEpochSecond(now));
        return temporaryReservation;
    }

    /** Minimal temporary reservation keys for the complete-phase delete that removes the temporary reservation once the hold settles. */
    private Reservation buildTemporaryReservationRef(Payment payment, Account account) {
        String reservationId = deriveReservationId(payment.getPaymentId());
        Reservation ref = new Reservation();
        ref.setAccountKey(account.getAccountKey());
        ref.setReservationKey(Reservation.TEMPORARY_KEY_PREFIX + reservationId);
        return ref;
    }

    /** Minimal reservation keys for complete-phase updates that reference an existing reservation. */
    private Reservation buildReservationRef(Payment payment, Account account) {
        String reservationId = deriveReservationId(payment.getPaymentId());
        Reservation ref = new Reservation();
        ref.setAccountKey(account.getAccountKey());
        ref.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        return ref;
    }

    /** Ledger line item for the debtor account when completing the payment. */
    private LedgerEntry buildLedgerEntry(Payment payment, Account account) {
        String ledgerEntryId = deriveLedgerEntryId(payment.getPaymentId());
        BigDecimal balanceAfter = account.getCurrentBalance().subtract(payment.getAmount());

        LedgerEntry entry = new LedgerEntry();
        entry.setAccountKey(account.getAccountKey());
        entry.setLedgerKey(LedgerEntry.KEY_PREFIX + payment.getCreatedAtUtc() + "#" + ledgerEntryId);
        entry.setEntityType(LedgerEntry.ENTITY_TYPE);
        entry.setLedgerEntryId(ledgerEntryId);
        entry.setPaymentId(payment.getPaymentId());
        entry.setEntryType("DEBIT");
        entry.setAmount(payment.getAmount());
        entry.setBalanceAfter(balanceAfter);
        entry.setCreatedAtUtc(Instant.now());
        return entry;
    }

    /**
     * Appends a domain event whose meaning is fully captured by {@link PaymentEventType} (and
     * optional {@code reasonCode} for {@link PaymentEventType#REJECTED}).
     */
    private PaymentEvent buildTransitionEvent(Payment payment,
                                              PaymentEventType type,
                                              long sequence,
                                              String correlationId,
                                              String reasonCode) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentKey(payment.getPaymentKey());
        event.setEventKey(PaymentEvent.sortKeyForSequence(sequence));
        event.setEntityType(PaymentEvent.ENTITY_TYPE);
        event.setEventType(type.name());
        event.setSequenceNumber(sequence);
        event.setCorrelationId(correlationId);
        event.setReasonCode(reasonCode);
        event.setOccurredAt(Instant.now());
        return event;
    }

    /**
     * Builds the post-reserve stream head used to complete without re-querying the partition.
     */
    private static PaymentStreamHead advanceHeadToReserved(PaymentStreamHead preReserveHead, Payment folded) {
        long reservedSequence = preReserveHead.getLastSequence() + 1;
        if (reservedSequence != folded.getVersion() + 1) {
            throw new IllegalStateException(
                    "Post-reserve head sequence does not match replayed aggregate for payment " + folded.getPaymentId());
        }

        PaymentStreamHead reserved = new PaymentStreamHead();
        reserved.setPaymentKey(preReserveHead.getPaymentKey());
        reserved.setStreamKey(PaymentStreamHead.SORT_KEY);
        reserved.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        reserved.setLastSequence(reservedSequence);
        reserved.setAggregateState(PaymentState.FUNDS_RESERVED.name());
        return reserved;
    }

    /**
     * Guards that the denormalised head matches the event fold (sequence equals {@link Payment#getVersion()}
     * and state strings match).
     */
    private static boolean headMatchesFold(PaymentStreamHead head, Payment folded) {
        return head.getLastSequence() == folded.getVersion()
                && head.getAggregateState().equals(folded.getState());
    }

    /**
     * @return whether {@code state} is {@link PaymentState#COMPLETED} or {@link PaymentState#REJECTED}
     */
    private static boolean isTerminal(String state) {
        return PaymentState.COMPLETED.name().equals(state) || PaymentState.REJECTED.name().equals(state);
    }

    /**
     * Normalises arbitrary throwables to a {@link CompletionException} for uniform handling.
     *
     * @param error raw failure from a future chain
     * @return the same instance when already a {@link CompletionException}, otherwise a new wrapper
     */
    private static CompletionException toCompletionException(Throwable error) {
        if (error instanceof CompletionException completionException) {
            return completionException;
        }
        return new CompletionException(error);
    }

    /** @return {@code true} if the failure is a DynamoDB conditional check cancellation */
    private static boolean isConditionalCheckFailed(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof TransactionCanceledException tce
                && tce.cancellationReasons().stream()
                .anyMatch(r -> "ConditionalCheckFailed".equals(r.code()));
    }

    /**
     * @return {@code true} if the transact was cancelled by a concurrent {@code TransactionConflict}
     */
    private static boolean isTransactionConflict(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof TransactionCanceledException tce
                && tce.cancellationReasons().stream()
                .anyMatch(r -> "TransactionConflict".equals(r.code()));
    }

    /**
     * Reports whether the cancellation reason at a specific transact item position is a conditional check failure.
     *
     * @param e     wrapper from a release transact failure
     * @param index transact item position to inspect (see {@link #RELEASE_RESERVATION_ITEM_INDEX} and
     *              {@link #RELEASE_ACCOUNT_ITEM_INDEX})
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

    /** Returns the underlying cause when present, otherwise the wrapper. */
    private static Throwable unwrap(CompletionException e) {
        return e.getCause() != null ? e.getCause() : e;
    }

    /** Stable reservation id derived from {@code paymentId}. */
    private static String deriveReservationId(String paymentId) {
        return "res_" + paymentId;
    }

    /**
     * Computes the hold deadline shared by the audit row and the temporary reservation.
     *
     * <p>The value is {@code now} plus {@code reservationTimeoutSeconds} as a Unix epoch second. The temporary
     * reservation stores it as the table {@code ttl} attribute, while both rows store it as {@code expiresAt} so the
     * complete transaction can reject a settlement that arrives after the deadline.
     *
     * @param now creation instant of the hold
     * @return the deadline as a Unix epoch second
     */
    private long holdDeadlineEpochSecond(Instant now) {
        return now.getEpochSecond() + reservationTimeoutSeconds;
    }

    /** Stable ledger entry id derived from {@code paymentId}. */
    private static String deriveLedgerEntryId(String paymentId) {
        return "led_" + paymentId;
    }

    /**
     * Persisted stream head together with the payment aggregate produced by replaying that partition's events.
     *
     * @param head   stream head row from the payment partition query
     * @param folded aggregate built by {@link PaymentEventReplayer#fold(String, List)}
     */
    private record LoadedPayment(PaymentStreamHead head, Payment folded) {
    }
}
