package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.LedgerEntry;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.ReservationBatchGetItemHelper;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactUpdateItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/**
 * High-level {@link PaymentRepository} implementation using {@link DynamoDbEnhancedAsyncClient}.
 *
 * <p>Most DynamoDB operations go through the enhanced async client - typed tables for queries and
 * gets, {@link TransactWriteItemsEnhancedRequest} for transactional writes. Batch reservation reads
 * ({@link #batchGetReservations}) use {@code BatchGetItem} on the underlying
 * {@link DynamoDbAsyncClient} from {@link DynamoDbEnhancedAsyncClient#dynamoDbAsyncClient()} so the
 * call shape stays {@code CompletableFuture}-based.
 *
 * <p>Merchant GSI queries ({@link #queryMerchantPayments}, {@link #queryMerchantPaymentsByState})
 * use {@link DynamoDbAsyncIndex} with {@link QueryEnhancedRequest#limit()}; only the first result
 * page is collected so the HTTP {@code limit} parameter is honored and continuation state is returned
 * as an opaque API token (see {@link #collectFirstQueryPage}).
 * Multi-attribute partition keys for {@link PaymentStreamHead#GSI_MERCHANT_STATE_PAYMENTS} are built
 * with {@link Key.Builder#addPartitionValue(Object)} for each partition component.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
public class HighLevelDynamoDbPaymentRepository implements PaymentRepository {

    /** Logger for this repository. */
    private static final Logger log = LoggerFactory.getLogger(HighLevelDynamoDbPaymentRepository.class);

    /** Enhanced async client for typed table and transaction APIs. */
    private final DynamoDbEnhancedAsyncClient enhancedClient;

    /**
     * Underlying low-level client (same instance as inside {@link #enhancedClient}) used for
     * {@code BatchGetItem} because the enhanced API is publisher based for that operation.
     */
    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    /** Typed view of payment stream head rows in the single table. */
    private final DynamoDbAsyncTable<PaymentStreamHead> streamHeadTable;

    /** Typed view of payment event rows in the single table. */
    private final DynamoDbAsyncTable<PaymentEvent> eventTable;

    /** Typed view of idempotency rows in the single table. */
    private final DynamoDbAsyncTable<IdempotencyRecord> idempotencyTable;

    /** Typed view of account rows in the single table. */
    private final DynamoDbAsyncTable<Account> accountTable;

    /** Typed view of reservation rows in the single table. */
    private final DynamoDbAsyncTable<Reservation> reservationTable;

    /** Typed view of ledger rows in the single table. */
    private final DynamoDbAsyncTable<LedgerEntry> ledgerTable;

    /** GSI handle for listing payments by merchant. */
    private final DynamoDbAsyncIndex<PaymentStreamHead> merchantPaymentsIndex;

    /** GSI handle for listing payments by merchant and aggregate state. */
    private final DynamoDbAsyncIndex<PaymentStreamHead> merchantStatePaymentsIndex;

    /** Physical single-table name from configuration ({@code dynamodb.table-name}). */
    private final String tableName;

    /**
     * Wires typed async tables and GSI index handles for the configured single-table name.
     *
     * @param enhancedClient enhanced async client (same underlying low-level client as elsewhere)
     * @param tableName      logical table name from configuration
     */
    public HighLevelDynamoDbPaymentRepository(DynamoDbEnhancedAsyncClient enhancedClient,
                                              @Value("${dynamodb.table-name}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.dynamoDbAsyncClient = enhancedClient.dynamoDbAsyncClient();
        this.tableName = tableName;
        this.streamHeadTable = enhancedClient.table(tableName, TableSchema.fromBean(PaymentStreamHead.class));
        this.eventTable = enhancedClient.table(tableName, TableSchema.fromBean(PaymentEvent.class));
        this.idempotencyTable = enhancedClient.table(tableName, TableSchema.fromBean(IdempotencyRecord.class));
        this.accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        this.reservationTable = enhancedClient.table(tableName, TableSchema.fromBean(Reservation.class));
        this.ledgerTable = enhancedClient.table(tableName, TableSchema.fromBean(LedgerEntry.class));
        this.merchantPaymentsIndex = streamHeadTable.index(PaymentStreamHead.GSI_MERCHANT_PAYMENTS);
        this.merchantStatePaymentsIndex = streamHeadTable.index(PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS);
        log.info("Initialized high-level DynamoDB payment repository for table '{}'", this.tableName);
    }

    @Override
    public CompletableFuture<Void> createPaymentTransaction(PaymentStreamHead streamHead,
                                                            PaymentEvent firstEvent,
                                                            IdempotencyRecord idempotency) {
        Expression idempotencyCondition = Expression.builder()
                .expression("attribute_not_exists(PK)")
                .build();

        TransactWriteItemsEnhancedRequest request = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(streamHeadTable, streamHead)
                .addPutItem(eventTable, firstEvent)
                .addPutItem(idempotencyTable,
                        TransactPutItemEnhancedRequest.builder(IdempotencyRecord.class)
                                .item(idempotency)
                                .conditionExpression(idempotencyCondition)
                                .build())
                .build();

        return enhancedClient.transactWriteItems(request);
    }

    @Override
    public CompletableFuture<IdempotencyRecord> getIdempotencyRecord(String idempotencyKey) {
        String partitionKey = IdempotencyRecord.KEY_PREFIX + idempotencyKey;
        Key key = Key.builder()
                .partitionValue(partitionKey)
                .sortValue(IdempotencyRecord.ENTITY_TYPE)
                .build();

        return idempotencyTable.getItem(r -> r.key(key).consistentRead(true));
    }

    @Override
    public CompletableFuture<PaymentPartitionQueryResult> queryPaymentPartition(String paymentId) {
        String pk = Payment.KEY_PREFIX + paymentId;

        CompletableFuture<PaymentStreamHead> headFuture = streamHeadTable.getItem(r -> r.key(
                Key.builder().partitionValue(pk).sortValue(PaymentStreamHead.SORT_KEY).build()));

        CompletableFuture<List<PaymentEvent>> eventsFuture = collectQueryItems(
                eventTable.query(QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.sortBeginsWith(
                                Key.builder().partitionValue(pk).sortValue(PaymentEvent.KEY_PREFIX).build()))
                        .build()));

        return headFuture.thenCombine(eventsFuture, (head, events) -> {
            if (head == null) {
                return null;
            }
            events.sort(Comparator.comparingLong(PaymentEvent::getSequenceNumber));
            return new PaymentPartitionQueryResult(head, List.copyOf(events));
        });
    }

    @Override
    public CompletableFuture<Account> getAccount(String accountId) {
        String keyValue = Account.KEY_PREFIX + accountId;
        Key key = Key.builder()
                .partitionValue(keyValue)
                .sortValue(keyValue)
                .build();

        return accountTable.getItem(r -> r.key(key));
    }

    @Override
    public CompletableFuture<AccountPartitionQueryResult> queryAccountPartition(String accountId) {
        String pk = Account.KEY_PREFIX + accountId;

        CompletableFuture<Account> accountFuture = accountTable.getItem(r -> r.key(
                Key.builder().partitionValue(pk).sortValue(pk).build()));

        CompletableFuture<List<Reservation>> reservationsFuture = collectQueryItems(
                reservationTable.query(QueryEnhancedRequest.builder()
                        .queryConditional(QueryConditional.sortBeginsWith(
                                Key.builder().partitionValue(pk).sortValue(Reservation.KEY_PREFIX).build()))
                        .build()));

        return accountFuture.thenCombine(reservationsFuture, (account, reservations) -> {
            if (account == null) {
                return null;
            }
            reservations.sort(Comparator.comparing(Reservation::getReservationKey));
            return new AccountPartitionQueryResult(account, List.copyOf(reservations));
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses {@link DynamoDbAsyncClient#batchGetItem} with keys from
     * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.ReservationBatchGetItemHelper}
     * (same layout as the low-level repository), then maps rows through {@link Reservation} metadata on
     * {@link #reservationTable}. Callers normally provide the service-layer validated identifier list;
     * this implementation keeps a small defensive deduplication/empty-input guard so repository behavior
     * remains stable if reused elsewhere.
     */
    @Override
    public CompletableFuture<BatchGetReservationsResult> batchGetReservations(String accountId,
                                                                             List<String> reservationIds) {
        // Defensive guard: callers usually send the already validated distinct list.
        List<String> distinctReservationIds = BatchGetItemHelper.distinctPreserveOrder(reservationIds);
        if (distinctReservationIds.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new BatchGetReservationsResult(List.of(), List.of()));
        }
        Map<String, KeysAndAttributes> requestItems =
                ReservationBatchGetItemHelper.buildReservationRequestItems(
                        tableName, accountId, distinctReservationIds);
        return BatchGetItemHelper.accumulateWithRetry(
                        requestItems,
                        request -> dynamoDbAsyncClient.batchGetItem(request),
                        this::mergeReservationBatchGetResponse,
                        log)
                .thenApply(reservationsByReservationId -> BatchGetItemHelper.toOrderedBatchGetResult(
                        distinctReservationIds,
                        reservationsByReservationId,
                        BatchGetReservationsResult::new));
    }

    /**
     * Maps reservation rows from one {@code BatchGetItem} response into the shared accumulator.
     */
    private void mergeReservationBatchGetResponse(BatchGetItemResponse response,
                                                  Map<String, Reservation> reservationsByReservationId) {
        ReservationBatchGetItemHelper.mergeReservationBatchGetResponse(
                tableName,
                response,
                reservationsByReservationId,
                reservationTable.tableSchema()::mapToItem);
    }

    @Override
    public CompletableFuture<Void> reserveFundsTransaction(PaymentStreamHead streamHead,
                                                           Account account,
                                                           Reservation reservation,
                                                           PaymentEvent event,
                                                           BigDecimal amount) {
        Instant now = Instant.now();
        long expectedSeq = streamHead.getLastSequence();
        long newSeq = expectedSeq + 1;

        TransactWriteItemsEnhancedRequest request = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(reservationTable, reservation)
                .addUpdateItem(accountTable, buildAccountDecrementAvailable(account, amount))
                .addUpdateItem(streamHeadTable, buildStreamHeadTransition(
                        streamHead, expectedSeq, PaymentState.RECEIVED.name(),
                        newSeq, PaymentState.FUNDS_RESERVED.name(), now))
                .addPutItem(eventTable, event)
                .build();

        return enhancedClient.transactWriteItems(request);
    }

    @Override
    public CompletableFuture<Void> completeFundsTransaction(PaymentStreamHead streamHead,
                                                            Account account,
                                                            Reservation reservation,
                                                            LedgerEntry ledgerEntry,
                                                            PaymentEvent event,
                                                            BigDecimal amount) {
        Instant now = Instant.now();
        long expectedSeq = streamHead.getLastSequence();
        long newSeq = expectedSeq + 1;

        Expression ledgerCondition = Expression.builder()
                .expression("attribute_not_exists(PK)")
                .build();

        TransactWriteItemsEnhancedRequest request = TransactWriteItemsEnhancedRequest.builder()
                .addUpdateItem(accountTable, buildAccountDecrementCurrent(account, amount))
                .addUpdateItem(reservationTable, buildReservationStatusUpdate(
                        reservation, ReservationStatus.CONSUMED.name()))
                .addPutItem(ledgerTable,
                        TransactPutItemEnhancedRequest.builder(LedgerEntry.class)
                                .item(ledgerEntry)
                                .conditionExpression(ledgerCondition)
                                .build())
                .addUpdateItem(streamHeadTable, buildStreamHeadTransition(
                        streamHead, expectedSeq, PaymentState.FUNDS_RESERVED.name(),
                        newSeq, PaymentState.COMPLETED.name(), now))
                .addPutItem(eventTable, event)
                .build();

        return enhancedClient.transactWriteItems(request);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queries {@link PaymentStreamHead#GSI_MERCHANT_PAYMENTS} via the enhanced index API.
     */
    @Override
    public CompletableFuture<MerchantPaymentQueryResult> queryMerchantPayments(String merchantId,
                                                                               int limit,
                                                                               boolean scanIndexForward,
                                                                               String nextToken) {
        QueryEnhancedRequest.Builder builder = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(merchantId).build()))
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        Map<String, AttributeValue> exclusiveStartKey = PaginationTokenCodec.decode(nextToken);
        if (exclusiveStartKey != null) {
            PaginationTokenCodec.requireKeyAttribute(
                    exclusiveStartKey,
                    PaginationTokenCodec.GSI_MERCHANT_PAYMENTS_DISCRIMINATOR,
                    nextToken);
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return collectFirstQueryPage(merchantPaymentsIndex.query(builder.build()))
                .thenApply(page -> new MerchantPaymentQueryResult(page.items(),
                        PaginationTokenCodec.encode(page.lastEvaluatedKey())));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queries {@link PaymentStreamHead#GSI_MERCHANT_STATE_PAYMENTS}; the composite partition key uses
     * {@code merchantId} and {@code state} via two {@link Key.Builder#addPartitionValue(Object)} calls;
     * the index sort key is {@code createdAtUtc}; traversal order is set via {@code scanIndexForward}.
     */
    @Override
    public CompletableFuture<MerchantPaymentQueryResult> queryMerchantPaymentsByState(String merchantId,
                                                                                      String state,
                                                                                      int limit,
                                                                                      boolean scanIndexForward,
                                                                                      String nextToken) {
        QueryEnhancedRequest.Builder builder = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder()
                                .addPartitionValue(merchantId)
                                .addPartitionValue(state)
                                .build()))
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        Map<String, AttributeValue> exclusiveStartKey = PaginationTokenCodec.decode(nextToken);
        if (exclusiveStartKey != null) {
            PaginationTokenCodec.requireKeyAttribute(
                    exclusiveStartKey,
                    PaginationTokenCodec.GSI_MERCHANT_STATE_PAYMENTS_DISCRIMINATOR,
                    nextToken);
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return collectFirstQueryPage(merchantStatePaymentsIndex.query(builder.build()))
                .thenApply(page -> new MerchantPaymentQueryResult(page.items(),
                        PaginationTokenCodec.encode(page.lastEvaluatedKey())));
    }

    @Override
    public CompletableFuture<Void> rejectPaymentTransaction(PaymentStreamHead streamHead,
                                                            PaymentEvent event,
                                                            String reasonCode) {
        Instant now = Instant.now();
        long expectedSeq = streamHead.getLastSequence();
        long newSeq = expectedSeq + 1;

        TransactWriteItemsEnhancedRequest request = TransactWriteItemsEnhancedRequest.builder()
                .addUpdateItem(streamHeadTable, buildStreamHeadReject(
                        streamHead, expectedSeq, newSeq, now, reasonCode))
                .addPutItem(eventTable, event)
                .build();

        return enhancedClient.transactWriteItems(request);
    }

    /**
     * Collects only the first page from a paginated enhanced query so
     * {@link QueryEnhancedRequest.Builder#limit()} is honored (merchant GSI list endpoints).
     *
     * <p>{@link #collectQueryItems} requests unbounded pages and would return every item in the
     * partition regardless of {@code limit}.
     *
     * @param pages publisher of result pages returned by {@link software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex#query}
     * @return future completing with the first page only
     */
    private <T> CompletableFuture<Page<T>> collectFirstQueryPage(SdkPublisher<Page<T>> pages) {
        List<T> items = new ArrayList<>();
        CompletableFuture<Page<T>> future = new CompletableFuture<>();
        pages.subscribe(new Subscriber<>() {
            private Subscription subscription;
            private Map<String, AttributeValue> lastEvaluatedKey = Map.of();

            /**
             * {@inheritDoc}
             */
            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onNext(Page<T> page) {
                items.addAll(page.items());
                lastEvaluatedKey = page.lastEvaluatedKey();
                subscription.cancel();
                future.complete(Page.create(items, lastEvaluatedKey));
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onComplete() {
                if (!future.isDone()) {
                    future.complete(Page.create(items, lastEvaluatedKey));
                }
            }
        });
        return future;
    }

    /**
     * Collects all items from a paginated enhanced-client query into a single list.
     *
     * @param pages publisher of result pages returned by {@link DynamoDbAsyncTable#query}
     * @return future completing with every item across all pages
     */
    private <T> CompletableFuture<List<T>> collectQueryItems(SdkPublisher<Page<T>> pages) {
        List<T> items = new ArrayList<>();
        CompletableFuture<List<T>> future = new CompletableFuture<>();
        pages.subscribe(new Subscriber<>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onNext(Page<T> page) {
                items.addAll(page.items());
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onError(Throwable t) {
                future.completeExceptionally(t);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onComplete() {
                future.complete(items);
            }
        });
        return future;
    }

    /**
     * Conditional update moving the stream head to {@code newSequence}/{@code newState} when prior sequence and state match.
     */
    private TransactUpdateItemEnhancedRequest<PaymentStreamHead> buildStreamHeadTransition(
            PaymentStreamHead head,
            long expectedSequence,
            String expectedState,
            long newSequence,
            String newState,
            Instant now) {
        PaymentStreamHead partial = new PaymentStreamHead();
        partial.setPaymentKey(head.getPaymentKey());
        partial.setStreamKey(PaymentStreamHead.SORT_KEY);
        partial.setLastSequence(newSequence);
        partial.setAggregateState(newState);
        partial.setUpdatedAtUtc(now);

        Expression condition = Expression.builder()
                .expression("lastSequence = :es AND aggregateState = :esState")
                .expressionValues(Map.of(
                        ":es", AttributeValue.builder().n(Long.toString(expectedSequence)).build(),
                        ":esState", AttributeValue.builder().s(expectedState).build()))
                .build();

        return TransactUpdateItemEnhancedRequest.builder(PaymentStreamHead.class)
                .item(partial)
                .ignoreNulls(true)
                .conditionExpression(condition)
                .build();
    }

    /**
     * Conditional update setting {@link PaymentState#REJECTED} and {@code reasonCode} from {@code RECEIVED} or {@code FUNDS_RESERVED}.
     */
    private TransactUpdateItemEnhancedRequest<PaymentStreamHead> buildStreamHeadReject(
            PaymentStreamHead head,
            long expectedSequence,
            long newSequence,
            Instant now,
            String reasonCode) {
        PaymentStreamHead partial = new PaymentStreamHead();
        partial.setPaymentKey(head.getPaymentKey());
        partial.setStreamKey(PaymentStreamHead.SORT_KEY);
        partial.setLastSequence(newSequence);
        partial.setAggregateState(PaymentState.REJECTED.name());
        partial.setUpdatedAtUtc(now);
        partial.setReasonCode(reasonCode);

        Expression condition = Expression.builder()
                .expression("lastSequence = :es AND aggregateState IN (:received, :fundsReserved)")
                .expressionValues(Map.of(
                        ":es", AttributeValue.builder().n(Long.toString(expectedSequence)).build(),
                        ":received", AttributeValue.builder().s(PaymentState.RECEIVED.name()).build(),
                        ":fundsReserved", AttributeValue.builder().s(PaymentState.FUNDS_RESERVED.name()).build()))
                .build();

        return TransactUpdateItemEnhancedRequest.builder(PaymentStreamHead.class)
                .item(partial)
                .ignoreNulls(true)
                .conditionExpression(condition)
                .build();
    }

    /**
     * Decrements available balance for reserve; requires sufficient funds while enhanced-client versioning guards
     * the optimistic {@code version} transition.
     */
    private TransactUpdateItemEnhancedRequest<Account> buildAccountDecrementAvailable(Account account,
                                                                                      BigDecimal amount) {
        Account partial = new Account();
        partial.setAccountKey(account.getAccountKey());
        partial.setEntityKey(account.getEntityKey());
        partial.setAvailableBalance(account.getAvailableBalance().subtract(amount));
        partial.setVersion(account.getVersion());

        Expression condition = Expression.builder()
                .expression("availableBalance >= :amt")
                .expressionValues(Map.of(
                        ":amt", AttributeValue.builder().n(amount.toPlainString()).build()))
                .build();

        return TransactUpdateItemEnhancedRequest.builder(Account.class)
                .item(partial)
                .ignoreNulls(true)
                .conditionExpression(condition)
                .build();
    }

    /**
     * Decrements posted {@code currentBalance} on completion; enhanced-client versioning guards the optimistic
     * {@code version} transition.
     */
    private TransactUpdateItemEnhancedRequest<Account> buildAccountDecrementCurrent(Account account,
                                                                                    BigDecimal amount) {
        Account partial = new Account();
        partial.setAccountKey(account.getAccountKey());
        partial.setEntityKey(account.getEntityKey());
        partial.setCurrentBalance(account.getCurrentBalance().subtract(amount));
        partial.setVersion(account.getVersion());

        return TransactUpdateItemEnhancedRequest.builder(Account.class)
                .item(partial)
                .ignoreNulls(true)
                .build();
    }

    /**
     * Sets reservation status (e.g. to {@link ReservationStatus#CONSUMED}) only when still {@link ReservationStatus#ACTIVE}.
     */
    private TransactUpdateItemEnhancedRequest<Reservation> buildReservationStatusUpdate(Reservation reservation,
                                                                                        String newStatus) {
        Reservation partial = new Reservation();
        partial.setAccountKey(reservation.getAccountKey());
        partial.setReservationKey(reservation.getReservationKey());
        partial.setStatus(newStatus);

        Expression condition = Expression.builder()
                .expression("#s = :expectedStatus")
                .expressionNames(Map.of("#s", "status"))
                .expressionValues(Map.of(
                        ":expectedStatus", AttributeValue.builder().s(
                                ReservationStatus.ACTIVE.name()).build()))
                .build();

        return TransactUpdateItemEnhancedRequest.builder(Reservation.class)
                .item(partial)
                .ignoreNulls(true)
                .conditionExpression(condition)
                .build();
    }
}
