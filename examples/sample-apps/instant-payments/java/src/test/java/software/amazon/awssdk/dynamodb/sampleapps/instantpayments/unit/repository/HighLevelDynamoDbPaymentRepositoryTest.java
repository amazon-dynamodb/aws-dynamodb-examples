package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.LedgerEntry;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.AccountPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.BatchGetReservationsResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.HighLevelDynamoDbPaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.MerchantPaymentQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HighLevelDynamoDbPaymentRepository}: enhanced-client wiring, partition queries,
 * transactional writes, and strongly consistent idempotency reads.
 *
 * <p>Uses mocked {@link DynamoDbEnhancedAsyncClient} and {@link DynamoDbAsyncTable} instances.
 * End-to-end behaviour is covered by integration tests.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
public class HighLevelDynamoDbPaymentRepositoryTest {

    private static final TableSchema<Account> ACCOUNT_SCHEMA = TableSchema.fromBean(Account.class);
    private static final TableSchema<Reservation> RESERVATION_SCHEMA = TableSchema.fromBean(Reservation.class);

    private static final String TABLE_NAME = "test-table";

    @Mock
    private DynamoDbEnhancedAsyncClient enhancedClient;

    @Mock
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    private DynamoDbAsyncTable<PaymentStreamHead> streamHeadTable;
    private DynamoDbAsyncTable<PaymentEvent> eventTable;
    private DynamoDbAsyncTable<IdempotencyRecord> idempotencyTable;
    private DynamoDbAsyncTable<Account> accountTable;
    private DynamoDbAsyncTable<Reservation> reservationTable;
    private DynamoDbAsyncTable<LedgerEntry> ledgerTable;
    private DynamoDbAsyncIndex<PaymentStreamHead> merchantIndex;

    private HighLevelDynamoDbPaymentRepository repository;

    /**
     * Wires {@link HighLevelDynamoDbPaymentRepository} with one mock table per enhanced
     * {@code table()} call and stubbed merchant GSIs on the stream-head table.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        streamHeadTable = mockTable(PaymentStreamHead.class);
        eventTable = mockTable(PaymentEvent.class);
        idempotencyTable = mockTable(IdempotencyRecord.class);
        accountTable = mockTable(Account.class);
        reservationTable = mockTable(Reservation.class);
        ledgerTable = mockTable(LedgerEntry.class);

        merchantIndex = mock(DynamoDbAsyncIndex.class);
        lenient().when(streamHeadTable.index(anyString())).thenReturn(merchantIndex);

        AtomicInteger tableCalls = new AtomicInteger(0);
        DynamoDbAsyncTable<?>[] tables = {
                streamHeadTable, eventTable, idempotencyTable,
                accountTable, reservationTable, ledgerTable };
        when(enhancedClient.table(anyString(), any(TableSchema.class)))
                .thenAnswer(inv -> tables[tableCalls.getAndIncrement()]);

        lenient().when(enhancedClient.dynamoDbAsyncClient()).thenReturn(dynamoDbAsyncClient);

        repository = new HighLevelDynamoDbPaymentRepository(enhancedClient, TABLE_NAME);
    }

    /**
     * Creates a mock {@link DynamoDbAsyncTable} whose {@code tableSchema()} and {@code tableName()}
     * return real values, so that {@link TransactWriteItemsEnhancedRequest} builders can serialise items.
     */
    private static <T> DynamoDbAsyncTable<T> mockTable(Class<T> beanClass) {
        DynamoDbAsyncTable<T> table = mock(DynamoDbAsyncTable.class);
        lenient().when(table.tableSchema()).thenReturn(TableSchema.fromBean(beanClass));
        lenient().when(table.tableName()).thenReturn(TABLE_NAME);
        return table;
    }

    /**
     * Verifies {@link HighLevelDynamoDbPaymentRepository#getIdempotencyRecord} passes
     * {@code consistentRead(true)} to the enhanced {@code GetItem} request.
     */
    @Test
    void getIdempotencyRecord_usesStronglyConsistentRead() {
        when(idempotencyTable.getItem(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.getIdempotencyRecord("idem-key").join();

        ArgumentCaptor<Consumer<GetItemEnhancedRequest.Builder>> captor =
                ArgumentCaptor.forClass(Consumer.class);
        verify(idempotencyTable).getItem(captor.capture());
        GetItemEnhancedRequest.Builder builder = GetItemEnhancedRequest.builder();
        captor.getValue().accept(builder);
        assertThat(builder.build().consistentRead()).isTrue();
    }

    @Test
    void createPaymentTransaction_callsTransactWriteItems() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.createPaymentTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildEvent("pay_1", 1),
                buildIdempotencyRecord("idem_1")
        ).join();

        verify(enhancedClient).transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));
    }

    @Test
    void queryPaymentPartition_headNotFound_returnsNull() {
        when(streamHeadTable.getItem(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(eventTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of()));

        PaymentPartitionQueryResult result = repository.queryPaymentPartition("pay_1").join();

        assertThat(result).isNull();
        verify(streamHeadTable).getItem(any(Consumer.class));
    }

    @Test
    void queryPaymentPartition_headFound_combinesHeadAndSortedEvents() {
        PaymentStreamHead head = buildStreamHead("pay_1", 2, "FUNDS_RESERVED");
        PaymentEvent event2 = buildEvent("pay_1", 2);
        PaymentEvent event1 = buildEvent("pay_1", 1);

        when(streamHeadTable.getItem(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(head));
        when(eventTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of(event2, event1)));

        PaymentPartitionQueryResult result = repository.queryPaymentPartition("pay_1").join();

        assertThat(result.streamHead()).isSameAs(head);
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().get(0).getSequenceNumber()).isEqualTo(1);
        assertThat(result.events().get(1).getSequenceNumber()).isEqualTo(2);
    }

    @Test
    void queryAccountPartition_accountNotFound_returnsNull() {
        when(accountTable.getItem(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(reservationTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of()));

        AccountPartitionQueryResult result = repository.queryAccountPartition("acc_1").join();

        assertThat(result).isNull();
        verify(accountTable).getItem(any(Consumer.class));
    }

    @Test
    void queryAccountPartition_accountFound_combinesAccountAndSortedReservations() {
        Account account = buildAccount("acc_1");
        Reservation resB = buildReservation("acc_1", "res_b");
        Reservation resA = buildReservation("acc_1", "res_a");

        when(accountTable.getItem(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(account));
        when(reservationTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of(resB, resA)));

        AccountPartitionQueryResult result = repository.queryAccountPartition("acc_1").join();

        assertThat(result.account()).isSameAs(account);
        assertThat(result.reservations()).hasSize(2);
        assertThat(result.reservations().get(0).getReservationKey())
                .isLessThan(result.reservations().get(1).getReservationKey());
    }

    @Test
    void batchGetReservations_allFound_returnsReservations() {
        Reservation resA = buildReservation("acc_a", "res_a");
        Reservation resB = buildReservation("acc_a", "res_b");

        when(dynamoDbAsyncClient.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        BatchGetItemResponse.builder()
                                .responses(Map.of(TABLE_NAME, List.of(
                                        RESERVATION_SCHEMA.itemToMap(resA, false),
                                        RESERVATION_SCHEMA.itemToMap(resB, false))))
                                .build()));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_b")).join();

        assertThat(result.reservations()).hasSize(2);
        assertThat(result.reservations().get(0).getReservationId()).isEqualTo("res_a");
        assertThat(result.reservations().get(1).getReservationId()).isEqualTo("res_b");
        assertThat(result.missingReservationIds()).isEmpty();
        verify(dynamoDbAsyncClient).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_partialMissing_returnsMissingIds() {
        Reservation resA = buildReservation("acc_a", "res_a");

        when(dynamoDbAsyncClient.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        BatchGetItemResponse.builder()
                                .responses(Map.of(TABLE_NAME, List.of(
                                        RESERVATION_SCHEMA.itemToMap(resA, false))))
                                .build()));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_ghost")).join();

        assertThat(result.reservations()).hasSize(1);
        assertThat(result.reservations().getFirst().getReservationId()).isEqualTo("res_a");
        assertThat(result.missingReservationIds()).containsExactly("res_ghost");
    }

    @Test
    void batchGetReservations_deduplicatesIds_beforeBatchGet() {
        Reservation resA = buildReservation("acc_a", "res_a");

        when(dynamoDbAsyncClient.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        BatchGetItemResponse.builder()
                                .responses(Map.of(TABLE_NAME, List.of(
                                        RESERVATION_SCHEMA.itemToMap(resA, false))))
                                .build()));

        repository.batchGetReservations("acc_a", List.of("res_a", "res_a")).join();

        ArgumentCaptor<BatchGetItemRequest> captor = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(dynamoDbAsyncClient).batchGetItem(captor.capture());
        assertThat(captor.getValue().requestItems().get(TABLE_NAME).keys()).hasSize(1);
    }

    @Test
    void batchGetReservations_emptyList_returnsImmediately() {
        BatchGetReservationsResult result = repository.batchGetReservations("acc_a", List.of()).join();

        assertThat(result.reservations()).isEmpty();
        assertThat(result.missingReservationIds()).isEmpty();
        verify(dynamoDbAsyncClient, never()).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_retriesUnprocessedKeys() {
        Reservation resA = buildReservation("acc_a", "res_a");
        Reservation resB = buildReservation("acc_a", "res_b");

        String pk = Account.KEY_PREFIX + "acc_a";
        String skB = Reservation.KEY_PREFIX + "res_b";
        Map<String, AttributeValue> keyBMap = Map.of(
                "PK", AttributeValue.builder().s(pk).build(),
                "SK", AttributeValue.builder().s(skB).build());

        BatchGetItemResponse firstResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(
                        RESERVATION_SCHEMA.itemToMap(resA, false))))
                .unprocessedKeys(Map.of(TABLE_NAME,
                        KeysAndAttributes.builder().keys(List.of(keyBMap)).build()))
                .build();
        BatchGetItemResponse secondResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(
                        RESERVATION_SCHEMA.itemToMap(resB, false))))
                .build();

        when(dynamoDbAsyncClient.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(firstResponse),
                        CompletableFuture.completedFuture(secondResponse));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_b")).join();

        assertThat(result.reservations()).hasSize(2);
        assertThat(result.missingReservationIds()).isEmpty();
        verify(dynamoDbAsyncClient, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_stopsRetryingAfterMaxAttempts() {
        Reservation resA = buildReservation("acc_a", "res_a");

        String pk = Account.KEY_PREFIX + "acc_a";
        String skB = Reservation.KEY_PREFIX + "res_b";
        Map<String, AttributeValue> keyBMap = Map.of(
                "PK", AttributeValue.builder().s(pk).build(),
                "SK", AttributeValue.builder().s(skB).build());

        BatchGetItemResponse throttledResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(
                        RESERVATION_SCHEMA.itemToMap(resA, false))))
                .unprocessedKeys(Map.of(TABLE_NAME,
                        KeysAndAttributes.builder().keys(List.of(keyBMap)).build()))
                .build();

        when(dynamoDbAsyncClient.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(throttledResponse));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_b")).join();

        assertThat(result.reservations()).hasSize(1);
        assertThat(result.reservations().getFirst().getReservationId()).isEqualTo("res_a");
        assertThat(result.missingReservationIds()).containsExactly("res_b");
        int expectedBatchGetCalls = BatchGetItemHelper.MAX_UNPROCESSED_RETRIES + 1;
        verify(dynamoDbAsyncClient, times(expectedBatchGetCalls)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void reserveFundsTransaction_callsTransactWriteItems() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.reserveFundsTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildEvent("pay_1", 2),
                new BigDecimal("100")
        ).join();

        verify(enhancedClient).transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));
    }

    @Test
    void reserveFundsTransaction_keepsOnlyBusinessConditionOnAccountUpdate() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.reserveFundsTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildEvent("pay_1", 2),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsEnhancedRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsEnhancedRequest.class);
        verify(enhancedClient).transactWriteItems(captor.capture());

        List<TransactWriteItem> items = captor.getValue().transactWriteItems();
        assertThat(items).hasSize(4);
        assertThat(items.get(1).update().conditionExpression()).isEqualTo("availableBalance >= :amt");
        assertThat(items.get(1).update().expressionAttributeValues())
                .containsEntry(":amt", AttributeValue.builder().n("100").build())
                .doesNotContainKey(":expectedVersion");
    }

    @Test
    void completeFundsTransaction_callsTransactWriteItems() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.completeFundsTransaction(
                buildStreamHead("pay_1", 2, "FUNDS_RESERVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildLedgerEntry("acc_1", "pay_1"),
                buildEvent("pay_1", 3),
                new BigDecimal("100")
        ).join();

        verify(enhancedClient).transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));
    }

    @Test
    void completeFundsTransaction_doesNotAddManualAccountVersionCondition() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.completeFundsTransaction(
                buildStreamHead("pay_1", 2, "FUNDS_RESERVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildLedgerEntry("acc_1", "pay_1"),
                buildEvent("pay_1", 3),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsEnhancedRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsEnhancedRequest.class);
        verify(enhancedClient).transactWriteItems(captor.capture());

        List<TransactWriteItem> items = captor.getValue().transactWriteItems();
        assertThat(items).hasSize(5);
        assertThat(items.getFirst().update().conditionExpression()).isNull();
    }

    @Test
    void queryMerchantPayments_returnsNextTokenAndReusesItAsExclusiveStartKey() {
        PaymentStreamHead head = buildStreamHead("pay_1", 1, "RECEIVED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:15:30Z").build(),
                "paymentId", AttributeValue.builder().s("pay_1").build());

        when(merchantIndex.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of(head), lastEvaluatedKey),
                        pagePublisherOf(List.of(), Map.of()));

        MerchantPaymentQueryResult firstPage = repository
                .queryMerchantPayments("merch_1", 1, false, null)
                .join();

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();

        repository.queryMerchantPayments("merch_1", 1, false, firstPage.nextToken()).join();

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(merchantIndex, times(2)).query(captor.capture());
        List<QueryEnhancedRequest> requests = captor.getAllValues();
        assertThat(requests.getFirst().exclusiveStartKey()).isNullOrEmpty();
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(lastEvaluatedKey);
    }

    @Test
    void queryMerchantPaymentsByState_returnsNextTokenAndReusesItAsExclusiveStartKey() {
        PaymentStreamHead head = buildStreamHead("pay_2", 2, "COMPLETED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "aggregateState", AttributeValue.builder().s("COMPLETED").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:16:00Z").build());

        when(merchantIndex.query(any(QueryEnhancedRequest.class)))
                .thenReturn(pagePublisherOf(List.of(head), lastEvaluatedKey),
                        pagePublisherOf(List.of(), Map.of()));

        MerchantPaymentQueryResult firstPage = repository
                .queryMerchantPaymentsByState("merch_1", "COMPLETED", 1, true, null)
                .join();

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();

        repository.queryMerchantPaymentsByState("merch_1", "COMPLETED", 1, true, firstPage.nextToken()).join();

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(merchantIndex, times(2)).query(captor.capture());
        List<QueryEnhancedRequest> requests = captor.getAllValues();
        assertThat(requests.getFirst().exclusiveStartKey()).isNullOrEmpty();
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(lastEvaluatedKey);
        assertThat(requests.get(1).scanIndexForward()).isTrue();
    }

    @Test
    void queryMerchantPayments_invalidNextToken_throws() {
        assertThatThrownBy(() -> repository.queryMerchantPayments("merch_1", 1, false, "bad-token").join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void queryMerchantPaymentsByState_invalidNextToken_throws() {
        assertThatThrownBy(() -> repository.queryMerchantPaymentsByState(
                "merch_1", "COMPLETED", 1, false, "bad-token").join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void rejectPaymentTransaction_callsTransactWriteItems() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.rejectPaymentTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildEvent("pay_1", 2),
                "INSUFFICIENT_FUNDS"
        ).join();

        verify(enhancedClient).transactWriteItems(any(TransactWriteItemsEnhancedRequest.class));
    }

    /**
     * Creates a {@link PagePublisher} that emits one {@link Page} with the given items, then completes.
     * Used to mock {@link DynamoDbAsyncTable#query} return values.
     */
    private static <T> PagePublisher<T> pagePublisherOf(List<T> items) {
        return PagePublisher.create(SdkPublisher.fromIterable(List.of(Page.create(items))));
    }

    private static <T> PagePublisher<T> pagePublisherOf(List<T> items, Map<String, AttributeValue> lastEvaluatedKey) {
        return PagePublisher.create(SdkPublisher.fromIterable(List.of(Page.create(items, lastEvaluatedKey))));
    }

    private static PaymentStreamHead buildStreamHead(String paymentId, long sequence, String state) {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(sequence);
        head.setAggregateState(state);
        head.setUpdatedAtUtc(Instant.now());
        head.setPaymentId(paymentId);
        head.setMerchantId("merch_1");
        head.setCreatedAtUtc(Instant.now());
        head.setCorrelationId("corr_test");
        head.setAmount(new BigDecimal("100"));
        head.setCurrency("USD");
        return head;
    }

    private static PaymentEvent buildEvent(String paymentId, long sequence) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        event.setEventKey(PaymentEvent.sortKeyForSequence(sequence));
        event.setEntityType(PaymentEvent.ENTITY_TYPE);
        event.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        event.setSequenceNumber(sequence);
        event.setOccurredAt(Instant.now());
        return event;
    }

    private static IdempotencyRecord buildIdempotencyRecord(String key) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordKey(IdempotencyRecord.KEY_PREFIX + key);
        record.setEntityKey(IdempotencyRecord.ENTITY_TYPE);
        record.setEntityType(IdempotencyRecord.ENTITY_TYPE);
        record.setRequestHash("sha256-test");
        record.setCreatedAtUtc(Instant.now());
        return record;
    }

    private static Account buildAccount(String accountId) {
        String key = Account.KEY_PREFIX + accountId;
        Account account = new Account();
        account.setAccountKey(key);
        account.setEntityKey(key);
        account.setEntityType(Account.ENTITY_TYPE);
        account.setAccountId(accountId);
        account.setStatus("ACTIVE");
        account.setCurrentBalance(new BigDecimal("10000"));
        account.setAvailableBalance(new BigDecimal("10000"));
        account.setCurrency("USD");
        account.setVersion(1);
        return account;
    }

    private static Reservation buildReservation(String accountId, String reservationId) {
        Reservation reservation = new Reservation();
        reservation.setAccountKey(Account.KEY_PREFIX + accountId);
        reservation.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        reservation.setEntityType(Reservation.ENTITY_TYPE);
        reservation.setReservationId(reservationId);
        reservation.setPaymentId("pay_test");
        reservation.setAmount(new BigDecimal("100"));
        reservation.setStatus(ReservationStatus.ACTIVE.name());
        reservation.setCreatedAtUtc(Instant.now());
        return reservation;
    }

    private static LedgerEntry buildLedgerEntry(String accountId, String paymentId) {
        LedgerEntry entry = new LedgerEntry();
        entry.setAccountKey(Account.KEY_PREFIX + accountId);
        entry.setLedgerKey(LedgerEntry.KEY_PREFIX + Instant.now() + "#led_" + paymentId);
        entry.setEntityType(LedgerEntry.ENTITY_TYPE);
        entry.setLedgerEntryId("led_" + paymentId);
        entry.setPaymentId(paymentId);
        entry.setEntryType("DEBIT");
        entry.setAmount(new BigDecimal("100"));
        entry.setBalanceAfter(new BigDecimal("9900"));
        entry.setCreatedAtUtc(Instant.now());
        return entry;
    }
}
