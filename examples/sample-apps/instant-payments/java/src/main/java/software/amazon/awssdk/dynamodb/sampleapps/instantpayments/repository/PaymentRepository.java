package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.*;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;

/**
 * Repository for payment-related DynamoDB operations.
 *
 * <p>Two implementations exist, selectable via {@code dynamodb.client-type}:
 * <ul>
 *   <li>{@code low-level} - uses {@code DynamoDbAsyncClient} with raw attribute maps</li>
 *   <li>{@code high-level} - uses {@code DynamoDbEnhancedAsyncClient} with annotated beans</li>
 * </ul>
 *
 * <p>The payment aggregate is <strong>event-sourced</strong>: {@link PaymentStreamHead} and
 * {@link PaymentEvent} rows live under {@code PAYMENT#}{@code id}. Merchant list APIs query GSIs that
 * project the stream head - see {@link #queryMerchantPayments} and {@link #queryMerchantPaymentsByState}.
 */
public interface PaymentRepository {

    /**
     * Atomically initializes the stream (head + first event) and an idempotency record.
     *
     * <p>Only the idempotency put uses {@code attribute_not_exists(PK)} (first-write-wins on the
     * client key). The head and first event are unconditional puts in the same transaction, so if
     * the idempotency condition fails the entire transaction rolls back and no payment rows appear.
     *
     * @param streamHead  initial {@link PaymentStreamHead} ({@code lastSequence=1}, RECEIVED)
     * @param firstEvent  {@code OUTBOUND_PAYMENT_CREATED}, sequence 1
     * @param idempotency idempotency item with conditional write
     */
    CompletableFuture<Void> createPaymentTransaction(PaymentStreamHead streamHead,
                                                     PaymentEvent firstEvent,
                                                     IdempotencyRecord idempotency);

    /**
     * Loads the idempotency item for a key using a <strong>strongly consistent</strong> {@code GetItem}.
     *
     * <p>Callers that read immediately after a transaction conflict on this key (e.g. duplicate
     * idempotency) rely on this consistency so a committed record is not missed by an eventually
     * consistent read.
     *
     * @param idempotencyKey client-supplied idempotency key
     * @return the record, or {@code null} if absent
     */
    CompletableFuture<IdempotencyRecord> getIdempotencyRecord(String idempotencyKey);

    /**
     * Loads the stream head and all events in one {@code Query}; events are sorted by sequence.
     *
     * @return {@code null} if no stream head exists for the payment
     */
    CompletableFuture<PaymentPartitionQueryResult> queryPaymentPartition(String paymentId);

    /**
     * Loads only the {@link Account} item ({@code PK=SK=ACCOUNT#id}), not reservations or ledger lines.
     *
     * @param accountId business account id
     * @return the account row, or {@code null} if missing
     */
    CompletableFuture<Account> getAccount(String accountId);

    /**
     * Queries the full account item collection: {@link Account} plus all {@link Reservation} rows under the same partition key.
     *
     * @param accountId business account id
     * @return account and reservations, or {@code null} if the account row is absent
     */
    CompletableFuture<AccountPartitionQueryResult> queryAccountPartition(String accountId);

    /**
     * Loads named reservation items for one account using {@code BatchGetItem}.
     *
     * <p>Each key uses partition attribute {@code PK} set to {@code ACCOUNT#} plus the
     * {@code accountId} and sort attribute {@code SK} set to {@code RESERVATION#} plus each
     * reservation id. The number of distinct ids should stay within the DynamoDB batch limit (100).
     * Callers normally pass the service-layer validated identifier list in first-seen distinct order;
     * implementations still preserve that ordering contract when building keys.
     *
     * <p>If DynamoDB returns {@code UnprocessedKeys}, implementations retry with exponential backoff
     * from {@link BatchGetItemHelper} up to {@link BatchGetItemHelper#MAX_UNPROCESSED_RETRIES} extra
     * rounds after the first response. When retries are exhausted, {@link BatchGetReservationsResult}
     * still returns every item that was read successfully and lists any ids that were never returned
     * (including ids that stayed in {@code UnprocessedKeys}) in {@link BatchGetReservationsResult#missingReservationIds()}.
     *
     * @param accountId      business account id used to build {@code PK}
     * @param reservationIds reservation ids to load, typically already deduplicated by the caller
     * @return found {@link Reservation} rows and missing ids in deduplicated request order
     */
    CompletableFuture<BatchGetReservationsResult> batchGetReservations(String accountId,
                                                                      List<String> reservationIds);

    /**
     * Atomically reserves funds and appends {@code FUNDS_RESERVED} in one {@code TransactWriteItems}.
     *
     * <p>Typical item set (all succeed or none apply):
     * <ul>
     *   <li>{@code Put} new {@link Reservation} under the account partition</li>
     *   <li>{@code Update} debtor {@link Account}: decrement {@code availableBalance}, conditional on
     *       balance and optimistic {@code version}</li>
     *   <li>{@code Update} {@link PaymentStreamHead}: {@code lastSequence} and {@code aggregateState}
     *       must match {@link PaymentState#RECEIVED} at {@link PaymentStreamHead#getLastSequence()},
     *       then advance to {@link PaymentState#FUNDS_RESERVED}</li>
     *   <li>{@code Put} the {@link PaymentEvent} row (next sequence)</li>
     * </ul>
     *
     * @param streamHead  head read before this call; supplies expected sequence and state for the update
     * @param account     debtor account as read before reserve (balances/version must still match at commit)
     * @param reservation new reservation item (idempotent with payment-derived id in implementations)
     * @param event       {@code FUNDS_RESERVED} with {@code sequenceNumber = lastSequence + 1}
     * @param amount      same monetary amount as the payment (applied to {@code availableBalance})
     */
    CompletableFuture<Void> reserveFundsTransaction(PaymentStreamHead streamHead,
                                                    Account account,
                                                    Reservation reservation,
                                                    PaymentEvent event,
                                                    BigDecimal amount);

    /**
     * Atomically finalises settlement and appends {@code COMPLETED} in one {@code TransactWriteItems}.
     *
     * <p>Typical item set:
     * <ul>
     *   <li>{@code Update} {@link Account}: decrement {@code currentBalance}, conditional on version</li>
     *   <li>{@code Update} {@link Reservation}: status to consumed, conditional on active reservation</li>
     *   <li>{@code Put} {@link LedgerEntry}</li>
     *   <li>{@code Update} {@link PaymentStreamHead}: must be {@link PaymentState#FUNDS_RESERVED} at
     *       expected sequence, then {@link PaymentState#COMPLETED}</li>
     *   <li>{@code Put} the {@link PaymentEvent} row</li>
     * </ul>
     *
     * @param streamHead   head read before this call
     * @param account      debtor account after reserve (fresh read recommended for version/balances)
     * @param reservation  keys + attributes identifying the active reservation to consume
     * @param ledgerEntry  new ledger line for the debit
     * @param event        {@code COMPLETED} with next sequence
     * @param amount       payment amount applied to {@code currentBalance}
     */
    CompletableFuture<Void> completeFundsTransaction(PaymentStreamHead streamHead,
                                                     Account account,
                                                     Reservation reservation,
                                                     LedgerEntry ledgerEntry,
                                                     PaymentEvent event,
                                                     BigDecimal amount);

    /**
     * Atomically rejects the payment: stream head transition and append-only {@code REJECTED} event.
     *
     * <p>Head must be {@link PaymentState#RECEIVED} or {@link PaymentState#FUNDS_RESERVED} at
     * {@link PaymentStreamHead#getLastSequence()} (implementations encode the allowed predecessor states in the
     * condition expression). No account or reservation mutation occurs in this transaction; if the
     * payment was reserved, separate operational compensation may be required in a fuller product.
     *
     * @param streamHead  head read before this call
     * @param event       {@code REJECTED} with next sequence and {@code reasonCode}
     * @param reasonCode  persisted on the head and event for diagnostics
     */
    CompletableFuture<Void> rejectPaymentTransaction(PaymentStreamHead streamHead,
                                                     PaymentEvent event,
                                                     String reasonCode);

    /**
     * Queries {@code GSI_MERCHANT_PAYMENTS} for payment projections belonging to a merchant.
     *
     * <p>Ordering follows DynamoDB {@code Query} {@code ScanIndexForward}: when {@code false},
     * results are newest-first (descending by the composed sort key {@code createdAtUtc + paymentId});
     * when {@code true}, oldest-first (ascending).
     *
     * @param merchantId        merchant partition key
     * @param limit             maximum number of items to return
     * @param scanIndexForward  {@code true} for ascending sort-key traversal, {@code false} for descending
     * @param nextToken         opaque pagination token from a previous page, or {@code null} for the first page
     * @return stream head items projected by the GSI plus an optional next-page token
     */
    CompletableFuture<MerchantPaymentQueryResult> queryMerchantPayments(String merchantId,
                                                                        int limit,
                                                                        boolean scanIndexForward,
                                                                        String nextToken);

    /**
     * Queries {@code GSI_MERCHANT_STATE_PAYMENTS} for payment projections matching a merchant and state.
     *
     * <p>Ordering follows DynamoDB {@code Query} {@code ScanIndexForward}: when {@code false},
     * results are newest-first (descending by {@code createdAtUtc}); when {@code true}, oldest-first.
     *
     * @param merchantId        merchant scope
     * @param state             payment state (must be upper-case {@link PaymentState} name)
     * @param limit             maximum number of items to return
     * @param scanIndexForward  {@code true} for ascending sort-key traversal, {@code false} for descending
     * @param nextToken         opaque pagination token from a previous page, or {@code null} for the first page
     * @return stream head items projected by the GSI plus an optional next-page token
     */
    CompletableFuture<MerchantPaymentQueryResult> queryMerchantPaymentsByState(String merchantId,
                                                                               String state,
                                                                               int limit,
                                                                               boolean scanIndexForward,
                                                                               String nextToken);
}
