package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Processes an outbound payment through the full lifecycle:
 * validate → reserve funds → complete (or reject).
 *
 * <p>Triggered by DynamoDB Streams ({@code INSERT} of the first payment event) or
 * {@code POST /{paymentId}/process}. Idempotency relies on conditional stream-head updates and
 * state checks, not on exactly-once delivery.
 *
 * <p><strong>Concurrency:</strong> Multiple invocations for the same {@code paymentId} are safe:
 * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository}
 * uses {@code TransactWriteItems} with optimistic conditions on the stream head, account
 * balances/versions, and reservation state. Losers receive {@code ConditionalCheckFailed} and this
 * class reconciles via {@link #handleReserveConflict(String)} or logs-and-skips on complete/reject.
 *
 * @apiNote The folded aggregate is always derived from stored events plus an invariant check against
 *     {@link PaymentStreamHead}; callers never pass ad-hoc state that could diverge from DynamoDB.
 */
@Service
public class OutboundPaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboundPaymentProcessor.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventReplayer paymentEventReplayer;

    /**
     * @param paymentRepository   persistence and transact operations for payments and accounts
     * @param paymentEventReplayer  folds event streams into {@link Payment} aggregates
     */
    public OutboundPaymentProcessor(PaymentRepository paymentRepository,
                                    PaymentEventReplayer paymentEventReplayer) {
        this.paymentRepository = paymentRepository;
        this.paymentEventReplayer = paymentEventReplayer;
    }

    /**
     * Processes a payment through validation, fund reservation, and completion/rejection.
     *
     * <p>Routing is by current aggregate state after replay: {@link PaymentState#RECEIVED} runs
     * validation and reserve (then complete in the same call if reserve succeeds);
     * {@link PaymentState#FUNDS_RESERVED} only runs completion (e.g. reserve succeeded elsewhere
     * or a prior call stopped after reserve). Terminal states short-circuit.
     *
     * @param paymentId logical payment identifier
     */
    public void processPayment(String paymentId) {
        LoadedPayment loaded = loadFolded(paymentId);
        Payment payment = loaded.folded();
        String state = payment.getState();
        String correlationId = payment.getCorrelationId();

        if (isTerminal(state)) {
            log.info("Payment {} already in terminal state {}, skipping", paymentId, state);
            return;
        }

        if (PaymentState.RECEIVED.name().equals(state)) {
            processFromReceived(payment, loaded.head(), correlationId);
        } else if (PaymentState.FUNDS_RESERVED.name().equals(state)) {
            completeFromFundsReserved(paymentId, correlationId);
        }
    }

    /**
     * Returns the current folded payment (replay of the event stream).
     *
     * @param paymentId logical payment identifier
     */
    public Payment getPayment(String paymentId) {
        return loadFolded(paymentId).folded();
    }

    /**
     * Continues processing from {@link PaymentState#RECEIVED}: validates account and balance, reserves funds, then completes.
     *
     * @param payment        aggregate in RECEIVED state
     * @param head           current stream head for conditional updates
     * @param correlationId  tracing id propagated to events
     */
    private void processFromReceived(Payment payment, PaymentStreamHead head, String correlationId) {
        Account account = paymentRepository.getAccount(payment.getDebtorAccountId()).join();

        if (account == null) {
            rejectPayment(payment.getPaymentId(), correlationId, "ACCOUNT_NOT_FOUND");
            return;
        }

        if (account.getAvailableBalance().compareTo(payment.getAmount()) < 0) {
            rejectPayment(payment.getPaymentId(), correlationId, "INSUFFICIENT_FUNDS");
            return;
        }

        if (!reserveFunds(payment, head, account, correlationId)) {
            return;
        }

        // Complete transact conditions include fresh account version and balances after the reserve.
        account = paymentRepository.getAccount(payment.getDebtorAccountId()).join();
        completePayment(payment.getPaymentId(), account, correlationId);
    }

    /**
     * Completes a payment already in {@link PaymentState#FUNDS_RESERVED} (e.g. after a prior reserve-only step).
     *
     * @param paymentId      payment identifier
     * @param correlationId  tracing id for completion event
     */
    private void completeFromFundsReserved(String paymentId, String correlationId) {
        LoadedPayment loaded = loadFolded(paymentId);
        Account account = paymentRepository.getAccount(loaded.folded().getDebtorAccountId()).join();
        completePayment(paymentId, account, correlationId);
    }

    /**
     * Runs the reserve transact: stream head, account balances, reservation row, and {@link PaymentEventType#FUNDS_RESERVED} event.
     *
     * @return {@code false} if a conditional conflict was handled via {@link #handleReserveConflict(String)}
     */
    private boolean reserveFunds(Payment payment, PaymentStreamHead head, Account account, String correlationId) {
        Reservation reservation = buildReservation(payment, account);
        long nextSeq = head.getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.FUNDS_RESERVED, nextSeq, correlationId, null);

        try {
            paymentRepository.reserveFundsTransaction(head, account, reservation, event, payment.getAmount()).join();
            log.info("Funds reserved for payment {}", payment.getPaymentId());
            return true;
        } catch (CompletionException e) {
            // Any conditional failure on the transact (head, account, or duplicate reservation) is treated as a race.
            if (isConditionalCheckFailed(e)) {
                handleReserveConflict(payment.getPaymentId());
                return false;
            }
            throw new RuntimeException("Reserve transaction failed for payment " + payment.getPaymentId(), unwrap(e));
        }
    }

    /**
     * Handles {@link TransactionCanceledException} with conditional failure from
     * {@link PaymentRepository#reserveFundsTransaction}: another writer changed the TransactWrite
     * preconditions (stream head, account balance, etc.). Re-loads the aggregate and:
     * <ul>
     *   <li><strong>Terminal</strong> ({@link PaymentState#COMPLETED} / {@link PaymentState#REJECTED}):
     *       no-op — already settled.</li>
     *   <li><strong>{@link PaymentState#FUNDS_RESERVED}</strong>: another path reserved first —
     *       run {@link #completePayment} here (caller must not complete again).</li>
     *   <li><strong>{@link PaymentState#RECEIVED}</strong>: reserve still lost and aggregate not
     *       advanced (e.g. account-side condition failed while payment head unchanged) —
     *       no-op at info; a later {@link #processPayment} or stream retry may succeed.</li>
     *   <li><strong>Any other</strong> non-terminal state string: warn — data or replay bug.</li>
     * </ul>
     */
    private void handleReserveConflict(String paymentId) {
        LoadedPayment loaded = loadFolded(paymentId);
        Payment fresh = loaded.folded();
        String state = fresh.getState();

        if (isTerminal(state)) {
            log.info("Payment {} already processed ({}), skipping", paymentId, state);
            return;
        }

        if (PaymentState.FUNDS_RESERVED.name().equals(state)) {
            Account account = paymentRepository.getAccount(fresh.getDebtorAccountId()).join();
            completePayment(paymentId, account, fresh.getCorrelationId());
            return;
        }

        if (PaymentState.RECEIVED.name().equals(state)) {
            log.info(
                    "Payment {} still {} after reserve conflict; skipping (another writer or account precondition lost the race)",
                    paymentId, state);
            return;
        }

        log.warn("Payment {} in non-terminal unexpected state after reserve conflict: {}", paymentId, state);
    }

    /**
     * Finalizes the payment: ledger debit, reservation consumed, {@link PaymentEventType#COMPLETED}, conditional on head sequence.
     *
     * @param paymentId      payment to complete
     * @param account        debtor account (fresh read for balances and version)
     * @param correlationId  tracing id on the completion event
     */
    private void completePayment(String paymentId, Account account, String correlationId) {
        LoadedPayment loaded = loadFolded(paymentId);
        Payment payment = loaded.folded();
        Reservation reservationRef = buildReservationRef(payment, account);
        LedgerEntry ledgerEntry = buildLedgerEntry(payment, account);
        long nextSeq = loaded.head().getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.COMPLETED, nextSeq, correlationId, null);

        try {
            paymentRepository.completeFundsTransaction(
                    loaded.head(), account, reservationRef, ledgerEntry, event, payment.getAmount()).join();
            log.info("Payment {} completed successfully", paymentId);
        } catch (CompletionException e) {
            // Head sequence or reservation/account preconditions: another writer completed first.
            if (isConditionalCheckFailed(e)) {
                log.info("Payment {} already completed by another processor", paymentId);
                return;
            }
            throw new RuntimeException("Complete transaction failed for payment " + paymentId, unwrap(e));
        }
    }

    /**
     * Appends {@link PaymentEventType#REJECTED} and updates the stream head when preconditions allow.
     *
     * @param reasonCode machine-oriented reason (e.g. {@code INSUFFICIENT_FUNDS})
     */
    private void rejectPayment(String paymentId, String correlationId, String reasonCode) {
        LoadedPayment loaded = loadFolded(paymentId);
        Payment payment = loaded.folded();
        long nextSeq = loaded.head().getLastSequence() + 1;
        PaymentEvent event = buildTransitionEvent(
                payment, PaymentEventType.REJECTED, nextSeq, correlationId, reasonCode);

        try {
            paymentRepository.rejectPaymentTransaction(loaded.head(), event, reasonCode).join();
            log.info("Payment {} rejected: {}", paymentId, reasonCode);
        } catch (CompletionException e) {
            if (isConditionalCheckFailed(e)) {
                log.info("Payment {} already in terminal state, skipping rejection", paymentId);
                return;
            }
            throw new RuntimeException("Reject transaction failed for payment " + paymentId, unwrap(e));
        }
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
     * Loads the payment partition, replays events, and checks head vs folded aggregate consistency.
     *
     * <p>The head row is the authoritative optimistic-lock and summary ({@code lastSequence},
     * {@code aggregateState}); replay must agree or the item collection is corrupt or partially written.
     *
     * @throws PaymentNotFoundException if the partition is absent
     */
    private LoadedPayment loadFolded(String paymentId) {
        PaymentPartitionQueryResult partition = paymentRepository.queryPaymentPartition(paymentId).join();
        if (partition == null) {
            throw new PaymentNotFoundException(paymentId);
        }
        Payment folded = paymentEventReplayer.fold(paymentId, partition.events());
        assertHeadMatchesFold(partition.streamHead(), folded);
        return new LoadedPayment(partition.streamHead(), folded);
    }

    /**
     * Guards that the denormalised head matches the event fold (sequence equals {@link Payment#getVersion()}
     * and state strings match).
     *
     * @throws IllegalStateException if {@code lastSequence} or {@code aggregateState} disagrees with the replayed payment
     */
    private static void assertHeadMatchesFold(PaymentStreamHead head, Payment folded) {
        if (head.getLastSequence() != folded.getVersion()
                || !head.getAggregateState().equals(folded.getState())) {
            throw new IllegalStateException(
                    "Stream head does not match replayed aggregate for payment " + folded.getPaymentId());
        }
    }

    /**
     * @return whether {@code state} is {@link PaymentState#COMPLETED} or {@link PaymentState#REJECTED}
     */
    private static boolean isTerminal(String state) {
        return PaymentState.COMPLETED.name().equals(state) || PaymentState.REJECTED.name().equals(state);
    }

    /** @return {@code true} if the failure is a DynamoDB conditional check cancellation */
    private static boolean isConditionalCheckFailed(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof TransactionCanceledException tce
                && tce.cancellationReasons().stream()
                .anyMatch(r -> "ConditionalCheckFailed".equals(r.code()));
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
     * <p>The head supplies {@code expectedSeq} for the next {@code TransactWriteItems}; the fold supplies
     * business fields and must be consistent with the head ({@link #assertHeadMatchesFold}).
     *
     * @param head   optimistic-lock row for the payment partition ({@code PAYMENT#…})
     * @param folded in-memory payment after {@link PaymentEventReplayer#fold}
     */
    private record LoadedPayment(PaymentStreamHead head, Payment folded) {
    }
}
