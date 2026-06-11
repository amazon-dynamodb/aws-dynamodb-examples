package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
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
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Low-level {@link PaymentRepository} implementation using {@link DynamoDbAsyncClient}.
 *
 * <p>Merchant GSI reads ({@link #queryMerchantPayments}, {@link #queryMerchantPaymentsByState}) use
 * raw {@link QueryRequest} with {@code KeyConditionExpression}. A single query call applies
 * {@link QueryRequest#limit()} and propagates {@code LastEvaluatedKey} through an opaque API token.
 * Batch reservation reads ({@link #batchGetReservations}) use {@code BatchGetItem} for RESERVATION
 * keys with application-level retry for unprocessed keys.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "low-level")
public class LowLevelDynamoDbPaymentRepository implements PaymentRepository {

    private static final Logger logger = LoggerFactory.getLogger(LowLevelDynamoDbPaymentRepository.class);

    /**
     * Page size for the expired-reservation {@code Scan}. The sweeper accumulates filtered matches across
     * pages, so this only bounds how many items each {@code Scan} call reads, not how many are released.
     */
    private static final int RESERVATION_SCAN_PAGE_SIZE = 100;

    /**
     * Upper bound on {@code Scan} pages walked in one expired-reservation sweep so a large table cannot make
     * a single sweep unbounded. Any remaining expired holds are picked up on the next sweep.
     */
    private static final int MAX_RESERVATION_SCAN_PAGES = 10;

    /**
     * Enhanced table schema for mapping {@link PaymentStreamHead} attribute maps.
     */
    private static final TableSchema<PaymentStreamHead> STREAM_HEAD_SCHEMA =
            TableSchema.fromBean(PaymentStreamHead.class);

    /**
     * Enhanced table schema for mapping {@link PaymentEvent} attribute maps.
     */
    private static final TableSchema<PaymentEvent> EVENT_SCHEMA = TableSchema.fromBean(PaymentEvent.class);

    /**
     * Enhanced table schema for mapping {@link IdempotencyRecord} attribute maps.
     */
    private static final TableSchema<IdempotencyRecord> IDEMPOTENCY_SCHEMA = TableSchema.fromBean(IdempotencyRecord.class);

    /**
     * Enhanced table schema for mapping {@link Account} attribute maps.
     */
    private static final TableSchema<Account> ACCOUNT_SCHEMA = TableSchema.fromBean(Account.class);

    /**
     * Enhanced table schema for mapping {@link Reservation} attribute maps.
     */
    private static final TableSchema<Reservation> RESERVATION_SCHEMA = TableSchema.fromBean(Reservation.class);

    /**
     * Enhanced table schema for mapping {@link LedgerEntry} attribute maps.
     */
    private static final TableSchema<LedgerEntry> LEDGER_SCHEMA = TableSchema.fromBean(LedgerEntry.class);

    /** Low-level DynamoDB async client used for every service call in this repository. */
    private final DynamoDbAsyncClient client;

    /** Physical single-table name from configuration ({@code dynamodb.table-name}). */
    private final String tableName;

    /**
     * @param client    low-level async client for all operations
     * @param tableName configured single-table name
     */
    public LowLevelDynamoDbPaymentRepository(DynamoDbAsyncClient client,
                                             @Value("${dynamodb.table-name}") String tableName) {
        this.client = client;
        this.tableName = tableName;
        logger.info("Initialized low-level DynamoDB payment repository: tableName={}", tableName);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> createPaymentTransaction(PaymentStreamHead streamHead,
                                                            PaymentEvent firstEvent,
                                                            IdempotencyRecord idempotency) {
        // Item positions follow CreateTransactItem so the order is defined once and shared with the
        // consumer that reads cancellation reasons by the same index.
        TransactWriteItem[] items = new TransactWriteItem[CreatePaymentTransactItemOrder.values().length];
        items[CreatePaymentTransactItemOrder.STREAM_HEAD.index()] = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(STREAM_HEAD_SCHEMA.itemToMap(streamHead, false))
                        .build())
                .build();
        items[CreatePaymentTransactItemOrder.FIRST_EVENT.index()] = putEventUnconditional(firstEvent);
        items[CreatePaymentTransactItemOrder.IDEMPOTENCY.index()] = TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(IDEMPOTENCY_SCHEMA.itemToMap(idempotency, false))
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build();

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(items)
                .build();

        return client.transactWriteItems(request).thenApply(r -> null);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<IdempotencyRecord> getIdempotencyRecord(String idempotencyKey) {
        String key = IdempotencyRecord.KEY_PREFIX + idempotencyKey;

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(key).build(),
                        "SK", AttributeValue.builder().s(IdempotencyRecord.ENTITY_TYPE).build()))
                .consistentRead(true)
                .build();

        return client.getItem(request)
                .thenApply(response -> {
                    if (!response.hasItem()) {
                        return null;
                    }
                    return IDEMPOTENCY_SCHEMA.mapToItem(response.item());
                });
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<PaymentPartitionQueryResult> queryPaymentPartition(String paymentId) {
        String pk = Payment.KEY_PREFIX + paymentId;
        PartitionScan acc = new PartitionScan();
        return queryPaymentPartitionPage(pk, null, acc);
    }

    /**
     * Recursively pages a payment-partition {@code Query}, classifying items into head and events.
     *
     * @param pk                 partition key value ({@code PAYMENT#…})
     * @param exclusiveStartKey  pagination token, or {@code null} for the first page
     * @param acc                mutable accumulator across pages
     * @return completed result or {@code null} if no head was found
     */
    private CompletableFuture<PaymentPartitionQueryResult> queryPaymentPartitionPage(
            String pk,
            Map<String, AttributeValue> exclusiveStartKey,
            PartitionScan acc) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                // Strongly consistent so the processor's read-before-write sees the latest committed
                // head and events, matching the high-level repository. Keeps the head-versus-fold guard
                // in OutboundPaymentProcessor reliable under concurrent writers.
                .consistentRead(true)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(pk).build()));
        if (exclusiveStartKey != null) {
            builder.exclusiveStartKey(exclusiveStartKey);
        }
        return client.query(builder.build())
                .thenCompose(response -> {
                    for (Map<String, AttributeValue> item : response.items()) {
                        AttributeValue typeAttr = item.get("entityType");
                        if (typeAttr == null || typeAttr.s() == null) {
                            continue;
                        }
                        String entityType = typeAttr.s();
                        if (PaymentStreamHead.ENTITY_TYPE.equals(entityType)) {
                            acc.streamHead = STREAM_HEAD_SCHEMA.mapToItem(item);
                        } else if (PaymentEvent.ENTITY_TYPE.equals(entityType)) {
                            acc.events.add(EVENT_SCHEMA.mapToItem(item));
                        }
                    }
                    if (response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()) {
                        return queryPaymentPartitionPage(pk, response.lastEvaluatedKey(), acc);
                    }
                    if (acc.streamHead == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    acc.events.sort(Comparator.comparingLong(PaymentEvent::getSequenceNumber));
                    return CompletableFuture.completedFuture(
                            new PaymentPartitionQueryResult(acc.streamHead, List.copyOf(acc.events)));
                });
    }

    /**
     * Mutable accumulator while scanning all pages of a payment partition query.
     */
    private static final class PartitionScan {
        /** Stream head row mapped from the payment partition query. */
        private PaymentStreamHead streamHead;
        /** Payment events collected across query pages. */
        private final List<PaymentEvent> events = new ArrayList<>();
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Account> getAccount(String accountId) {
        String key = Account.KEY_PREFIX + accountId;

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(key).build(),
                        "SK", AttributeValue.builder().s(key).build()))
                .build();

        return client.getItem(request)
                .thenApply(response -> response.hasItem() ? ACCOUNT_SCHEMA.mapToItem(response.item()) : null);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<AccountPartitionQueryResult> queryAccountPartition(String accountId) {
        String pk = Account.KEY_PREFIX + accountId;
        AccountPartitionScan acc = new AccountPartitionScan();
        return queryAccountPartitionPage(pk, null, acc);
    }

    /**
     * Recursively pages an account-partition {@code Query}, collecting the account row and reservations.
     *
     * @param pk                 partition key value ({@code ACCOUNT#…})
     * @param exclusiveStartKey  pagination token, or {@code null} for the first page
     * @param acc                mutable accumulator across pages
     * @return completed result or {@code null} if the account row is absent
     */
    private CompletableFuture<AccountPartitionQueryResult> queryAccountPartitionPage(
            String pk,
            Map<String, AttributeValue> exclusiveStartKey,
            AccountPartitionScan acc) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(pk).build()));
        if (exclusiveStartKey != null) {
            builder.exclusiveStartKey(exclusiveStartKey);
        }
        return client.query(builder.build())
                .thenCompose(response -> {
                    for (Map<String, AttributeValue> item : response.items()) {
                        AttributeValue typeAttr = item.get("entityType");
                        if (typeAttr == null || typeAttr.s() == null) {
                            continue;
                        }
                        String entityType = typeAttr.s();
                        if (Account.ENTITY_TYPE.equals(entityType)) {
                            acc.account = ACCOUNT_SCHEMA.mapToItem(item);
                        } else if (Reservation.ENTITY_TYPE.equals(entityType)) {
                            acc.reservations.add(RESERVATION_SCHEMA.mapToItem(item));
                        }
                    }
                    if (response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()) {
                        return queryAccountPartitionPage(pk, response.lastEvaluatedKey(), acc);
                    }
                    if (acc.account == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    acc.reservations.sort(Comparator.comparing(Reservation::getReservationKey));
                    return CompletableFuture.completedFuture(
                            new AccountPartitionQueryResult(acc.account, List.copyOf(acc.reservations)));
                });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Implements {@link PaymentRepository#batchGetReservations(String, List)} with raw
     * {@link BatchGetItemRequest} maps, keys from
     * {@link ReservationBatchGetItemHelper},
     * and the {@code RESERVATION_SCHEMA} static schema for item mapping.
     * Callers normally provide the service-layer validated identifier list. This implementation keeps a
     * small defensive deduplication/empty-input guard so repository behavior remains stable if reused elsewhere.
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
                        client::batchGetItem,
                        this::mergeReservationBatchGetResponse,
                        logger)
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
                RESERVATION_SCHEMA::mapToItem);
    }

    /**
     * Mutable accumulator while scanning all pages of an account partition query.
     */
    private static final class AccountPartitionScan {
        /** Account row mapped from the account partition query. */
        private Account account;
        /** Reservations collected across query pages. */
        private final List<Reservation> reservations = new ArrayList<>();
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> reserveFundsTransaction(PaymentStreamHead streamHead,
                                                           Account account,
                                                           Reservation reservation,
                                                           PaymentEvent event,
                                                           BigDecimal amount) {
        Instant now = Instant.now();
        long expectedSeq = streamHead.getLastSequence();
        long newSeq = expectedSeq + 1;

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        putReservation(reservation),
                        updateAccountDecrementAvailable(account, amount),
                        updateStreamHeadTransition(streamHead, expectedSeq, PaymentState.RECEIVED.name(),
                                newSeq, PaymentState.FUNDS_RESERVED.name(), now),
                        putEventUnconditional(event))
                .build();

        return client.transactWriteItems(request).thenApply(r -> null);
    }

    /** {@inheritDoc} */
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

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        updateAccountDecrementCurrent(account, amount),
                        updateReservationStatus(reservation, ReservationStatus.CONSUMED.name()),
                        putLedgerEntry(ledgerEntry),
                        updateStreamHeadTransition(streamHead, expectedSeq, PaymentState.FUNDS_RESERVED.name(),
                                newSeq, PaymentState.COMPLETED.name(), now),
                        putEventUnconditional(event))
                .build();

        return client.transactWriteItems(request).thenApply(r -> null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses index {@link PaymentStreamHead#GSI_MERCHANT_PAYMENTS} with {@code merchantId = :mid}.
     */
    @Override
    public CompletableFuture<MerchantPaymentQueryResult> queryMerchantPayments(String merchantId,
                                                                               int limit,
                                                                               boolean scanIndexForward,
                                                                               String nextToken) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .indexName(PaymentStreamHead.GSI_MERCHANT_PAYMENTS)
                .keyConditionExpression("merchantId = :mid")
                .expressionAttributeValues(Map.of(
                        ":mid", AttributeValue.builder().s(merchantId).build()))
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        Map<String, AttributeValue> exclusiveStartKey = PaginationTokenCodec.decode(nextToken);
        if (exclusiveStartKey != null) {
            PaginationTokenCodec.requireKeyAttribute(
                    exclusiveStartKey,
                    PaginationTokenCodec.GSI_MERCHANT_PAYMENTS_DISCRIMINATOR,
                    nextToken);
            PaginationTokenCodec.requireMatchingMerchantId(exclusiveStartKey, merchantId, nextToken);
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return client.query(builder.build())
                .thenApply(response -> new MerchantPaymentQueryResult(
                        response.items().stream()
                                .map(STREAM_HEAD_SCHEMA::mapToItem)
                                .toList(),
                        PaginationTokenCodec.encode(response.lastEvaluatedKey())));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uses index {@link PaymentStreamHead#GSI_MERCHANT_STATE_PAYMENTS} with
     * {@code merchantId = :mid AND aggregateState = :st}.
     */
    @Override
    public CompletableFuture<MerchantPaymentQueryResult> queryMerchantPaymentsByState(String merchantId,
                                                                                      String state,
                                                                                      int limit,
                                                                                      boolean scanIndexForward,
                                                                                      String nextToken) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .indexName(PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS)
                .keyConditionExpression("merchantId = :mid AND aggregateState = :st")
                .expressionAttributeValues(Map.of(
                        ":mid", AttributeValue.builder().s(merchantId).build(),
                        ":st", AttributeValue.builder().s(state).build()))
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        Map<String, AttributeValue> exclusiveStartKey = PaginationTokenCodec.decode(nextToken);
        if (exclusiveStartKey != null) {
            PaginationTokenCodec.requireKeyAttribute(
                    exclusiveStartKey,
                    PaginationTokenCodec.GSI_MERCHANT_STATE_PAYMENTS_DISCRIMINATOR,
                    nextToken);
            PaginationTokenCodec.requireMatchingMerchantId(exclusiveStartKey, merchantId, nextToken);
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return client.query(builder.build())
                .thenApply(response -> new MerchantPaymentQueryResult(
                        response.items().stream()
                                .map(STREAM_HEAD_SCHEMA::mapToItem)
                                .toList(),
                        PaginationTokenCodec.encode(response.lastEvaluatedKey())));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> rejectPaymentTransaction(PaymentStreamHead streamHead,
                                                            PaymentEvent event,
                                                            String reasonCode) {
        Instant now = Instant.now();
        long expectedSeq = streamHead.getLastSequence();
        long newSeq = expectedSeq + 1;

        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        updateStreamHeadReject(streamHead, expectedSeq, newSeq, now, reasonCode),
                        putEventUnconditional(event))
                .build();

        return client.transactWriteItems(request).thenApply(r -> null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs a {@code Scan} with a server-side {@code FilterExpression} and accumulates matched rows across
     * pages up to {@code limit} or {@link #MAX_RESERVATION_SCAN_PAGES}.
     */
    @Override
    public CompletableFuture<List<Reservation>> scanExpiredActiveReservations(long nowEpochSecond, int limit) {
        return scanExpiredActiveReservationsPage(nowEpochSecond, limit, null, new ArrayList<>(), 0);
    }

    /**
     * Recursively pages the expired-reservation {@code Scan}, accumulating matches until {@code limit} rows are
     * collected, the table is exhausted, or {@link #MAX_RESERVATION_SCAN_PAGES} pages have been read.
     *
     * @param nowEpochSecond    expiry cutoff as a Unix epoch second
     * @param limit             maximum reservations to accumulate
     * @param exclusiveStartKey pagination token, or {@code null} for the first page
     * @param acc               mutable accumulator across pages
     * @param page              0-based page index for the page cap
     * @return completed future of the accumulated expired reservations
     */
    private CompletableFuture<List<Reservation>> scanExpiredActiveReservationsPage(
            long nowEpochSecond,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey,
            List<Reservation> acc,
            int page) {
        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(tableName)
                .filterExpression("entityType = :res AND #s = :active AND expiresAt <= :now")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":res", AttributeValue.builder().s(Reservation.ENTITY_TYPE).build(),
                        ":active", AttributeValue.builder().s(ReservationStatus.ACTIVE.name()).build(),
                        ":now", AttributeValue.builder().n(Long.toString(nowEpochSecond)).build()))
                .limit(RESERVATION_SCAN_PAGE_SIZE);
        if (exclusiveStartKey != null) {
            builder.exclusiveStartKey(exclusiveStartKey);
        }
        return client.scan(builder.build())
                .thenCompose(response -> {
                    for (Map<String, AttributeValue> item : response.items()) {
                        if (acc.size() >= limit) {
                            break;
                        }
                        acc.add(RESERVATION_SCHEMA.mapToItem(item));
                    }
                    boolean morePages = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty();
                    if (acc.size() < limit && morePages && page + 1 < MAX_RESERVATION_SCAN_PAGES) {
                        return scanExpiredActiveReservationsPage(
                                nowEpochSecond, limit, response.lastEvaluatedKey(), acc, page + 1);
                    }
                    return CompletableFuture.completedFuture(List.copyOf(acc));
                });
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> releaseReservationTransaction(Reservation reservation,
                                                                 Account account,
                                                                 BigDecimal amount) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder()
                .transactItems(
                        updateReservationStatus(reservation, ReservationStatus.RELEASED.name()),
                        updateAccountIncrementAvailable(account, amount))
                .build();

        return client.transactWriteItems(request).thenApply(r -> null);
    }

    /**
     * Unconditional {@code Put} for a new {@link Reservation} item.
     */
    private TransactWriteItem putReservation(Reservation reservation) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(RESERVATION_SCHEMA.itemToMap(reservation, false))
                        .build())
                .build();
    }

    /**
     * Conditional {@code Put} for a ledger line ({@code attribute_not_exists(PK)}) to enforce write-once semantics.
     */
    private TransactWriteItem putLedgerEntry(LedgerEntry ledgerEntry) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(LEDGER_SCHEMA.itemToMap(ledgerEntry, false))
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build();
    }

    /**
     * Unconditional {@code Put} for an append-only {@link PaymentEvent}.
     */
    private TransactWriteItem putEventUnconditional(PaymentEvent event) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(EVENT_SCHEMA.itemToMap(event, true))
                        .build())
                .build();
    }

    /**
     * Updates the stream head sequence and aggregate state when {@code lastSequence} and prior state match expectations.
     */
    private TransactWriteItem updateStreamHeadTransition(PaymentStreamHead head,
                                                         long expectedSequence,
                                                         String expectedState,
                                                         long newSequence,
                                                         String newState,
                                                         Instant now) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(head.getPaymentKey()).build(),
                                "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                        .updateExpression("SET lastSequence = :ns, aggregateState = :nsState, updatedAtUtc = :now")
                        .conditionExpression("lastSequence = :es AND aggregateState = :esState")
                        .expressionAttributeValues(Map.of(
                                ":ns", AttributeValue.builder().n(Long.toString(newSequence)).build(),
                                ":nsState", AttributeValue.builder().s(newState).build(),
                                ":es", AttributeValue.builder().n(Long.toString(expectedSequence)).build(),
                                ":esState", AttributeValue.builder().s(expectedState).build(),
                                ":now", AttributeValue.builder().s(now.toString()).build()))
                        .build())
                .build();
    }

    /**
     * Sets head to {@link PaymentState#REJECTED} with {@code reasonCode} when still in {@code RECEIVED} or {@code FUNDS_RESERVED}.
     */
    private TransactWriteItem updateStreamHeadReject(PaymentStreamHead head,
                                                     long expectedSequence,
                                                     long newSequence,
                                                     Instant now,
                                                     String reasonCode) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(head.getPaymentKey()).build(),
                                "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                        .updateExpression(
                                "SET lastSequence = :ns, aggregateState = :nsState, updatedAtUtc = :now, reasonCode = :rc")
                        .conditionExpression(
                                "lastSequence = :es AND aggregateState IN (:received, :fundsReserved)")
                        .expressionAttributeValues(Map.of(
                                ":ns", AttributeValue.builder().n(Long.toString(newSequence)).build(),
                                ":nsState", AttributeValue.builder().s(PaymentState.REJECTED.name()).build(),
                                ":es", AttributeValue.builder().n(Long.toString(expectedSequence)).build(),
                                ":received", AttributeValue.builder().s(PaymentState.RECEIVED.name()).build(),
                                ":fundsReserved", AttributeValue.builder().s(PaymentState.FUNDS_RESERVED.name()).build(),
                                ":now", AttributeValue.builder().s(now.toString()).build(),
                                ":rc", AttributeValue.builder().s(reasonCode).build()))
                        .build())
                .build();
    }

    /**
     * Decrements {@code availableBalance} for a reserve, with balance and optimistic-version preconditions.
     */
    private TransactWriteItem updateAccountDecrementAvailable(Account account, BigDecimal amount) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(account.getAccountKey()).build(),
                                "SK", AttributeValue.builder().s(account.getEntityKey()).build()))
                        .updateExpression("SET availableBalance = availableBalance - :amt, version = version + :one")
                        .conditionExpression("availableBalance >= :amt AND version = :expectedVersion")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.builder().n(amount.toPlainString()).build(),
                                ":one", AttributeValue.builder().n("1").build(),
                                ":expectedVersion", AttributeValue.builder().n(String.valueOf(account.getVersion())).build()))
                        .build())
                .build();
    }

    /**
     * Decrements {@code currentBalance} on settlement (complete phase). Requires matching optimistic {@code version}.
     */
    private TransactWriteItem updateAccountDecrementCurrent(Account account, BigDecimal amount) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(account.getAccountKey()).build(),
                                "SK", AttributeValue.builder().s(account.getEntityKey()).build()))
                        .updateExpression("SET currentBalance = currentBalance - :amt, version = version + :one")
                        .conditionExpression("version = :expectedVersion")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.builder().n(amount.toPlainString()).build(),
                                ":one", AttributeValue.builder().n("1").build(),
                                ":expectedVersion", AttributeValue.builder().n(String.valueOf(account.getVersion())).build()))
                        .build())
                .build();
    }

    /**
     * Increments {@code availableBalance} when releasing an expired hold. Requires matching optimistic
     * {@code version}. No balance floor applies because returning held funds only ever increases the balance.
     */
    private TransactWriteItem updateAccountIncrementAvailable(Account account, BigDecimal amount) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(account.getAccountKey()).build(),
                                "SK", AttributeValue.builder().s(account.getEntityKey()).build()))
                        .updateExpression("SET availableBalance = availableBalance + :amt, version = version + :one")
                        .conditionExpression("version = :expectedVersion")
                        .expressionAttributeValues(Map.of(
                                ":amt", AttributeValue.builder().n(amount.toPlainString()).build(),
                                ":one", AttributeValue.builder().n("1").build(),
                                ":expectedVersion", AttributeValue.builder().n(String.valueOf(account.getVersion())).build()))
                        .build())
                .build();
    }

    /**
     * Updates reservation {@code status} when the current value is {@link ReservationStatus#ACTIVE}.
     */
    private TransactWriteItem updateReservationStatus(Reservation reservation, String newStatus) {
        String conditionStatus = ReservationStatus.ACTIVE.name();

        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(reservation.getAccountKey()).build(),
                                "SK", AttributeValue.builder().s(reservation.getReservationKey()).build()))
                        .updateExpression("SET #s = :newStatus")
                        .conditionExpression("#s = :expectedStatus")
                        .expressionAttributeNames(Map.of("#s", "status"))
                        .expressionAttributeValues(Map.of(
                                ":newStatus", AttributeValue.builder().s(newStatus).build(),
                                ":expectedStatus", AttributeValue.builder().s(conditionStatus).build()))
                        .build())
                .build();
    }
}
