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

    /** Persistence and transact operations for payments and accounts. */
    private final PaymentRepository paymentRepository;
    /** Folds event streams into {@link Payment} aggregates. */
    private final PaymentEventReplayer paymentEventReplayer;
    /** Async delay and composition helpers for retry backoffs. */
    private final AsyncSupport asyncSupport;
    /**
     * Seconds a reservation hold stays valid. A new reservation's {@code expiresAt} is set to
     * {@code createdAt + this}, after which the expiry sweeper may release the hold. From
     * {@code dynamodb.reservation-timeout-seconds}.
     */
    private final long reservationTimeoutSeconds;

    /**
     * @param paymentRepository   persistence and transact operations for payments and accounts
     * @param paymentEventReplayer  folds event streams into {@link Payment} aggregates
     * @param asyncSupport        delay and async composition helpers for in-call retries
     * @param reservationTimeoutSeconds  hold lifetime in seconds, used to stamp {@code expiresAt} on new reservations
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
        Reservation reservation = buildReservation(payment, account);
        long nextSeq = head.getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.FUNDS_RESERVED, nextSeq, correlationId, null);

        return AsyncSupport.exceptionallyCompose(
                paymentRepository.reserveFundsTransaction(head, account, reservation, event, payment.getAmount())
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
        LedgerEntry ledgerEntry = buildLedgerEntry(payment, account);
        long nextSeq = head.getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.COMPLETED, nextSeq, correlationId, null);

        return AsyncSupport.exceptionallyCompose(
                paymentRepository.completeFundsTransaction(
                                head, account, reservationRef, ledgerEntry, event, payment.getAmount())
                        .thenApply(ignored -> {
                            logger.debug("Outbound payment completed: paymentId={}", paymentId);
                            return (Void) null;
                        }),
                error -> recoverCompleteFailure(paymentId, error));
    }

    /**
     * Maps complete transact failures to a no-op on conditional conflict or a propagated error.
     *
     * @param paymentId payment that failed to complete
     * @param error     failure from the complete future chain
     * @return void future that succeeds when another processor already completed the payment
     */
    private CompletableFuture<Void> recoverCompleteFailure(String paymentId, Throwable error) {
        CompletionException completionException = toCompletionException(error);
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

    /** Builds a full {@link Reservation} item for the reserve transact. */
    private Reservation buildReservation(Payment payment, Account account) {
        String reservationId = deriveReservationId(payment.getPaymentId());
        Instant now = Instant.now();

        Reservation reservation = new Reservation();
        reservation.setAccountKey(account.getAccountKey());
        reservation.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        reservation.setEntityType(Reservation.ENTITY_TYPE);
        reservation.setReservationId(reservationId);
        reservation.setPaymentId(payment.getPaymentId());
        reservation.setAmount(payment.getAmount());
        reservation.setStatus(ReservationStatus.ACTIVE.name());
        reservation.setCreatedAtUtc(now);
        reservation.setExpiresAt(now.getEpochSecond() + reservationTimeoutSeconds);
        return reservation;
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

    /** Returns the underlying cause when present, otherwise the wrapper. */
    private static Throwable unwrap(CompletionException e) {
        return e.getCause() != null ? e.getCause() : e;
    }

    /** Stable reservation id derived from {@code paymentId}. */
    private static String deriveReservationId(String paymentId) {
        return "res_" + paymentId;
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
