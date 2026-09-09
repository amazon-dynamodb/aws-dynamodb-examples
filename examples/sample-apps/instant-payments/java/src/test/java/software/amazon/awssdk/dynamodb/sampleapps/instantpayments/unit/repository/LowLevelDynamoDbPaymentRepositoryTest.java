package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.CreatePaymentTransactItemOrder;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.LowLevelDynamoDbPaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.MerchantPaymentQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LowLevelDynamoDbPaymentRepository}: raw client calls, partition scans,
 * transactional writes, and strongly consistent idempotency reads.
 *
 * <p>Uses mocked {@link DynamoDbAsyncClient}. End-to-end behaviour is covered by integration tests.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class LowLevelDynamoDbPaymentRepositoryTest {

    private static final TableSchema<PaymentStreamHead> STREAM_HEAD_SCHEMA =
            TableSchema.fromBean(PaymentStreamHead.class);

    private static final TableSchema<PaymentEvent> EVENT_SCHEMA = TableSchema.fromBean(PaymentEvent.class);

    private static final TableSchema<Account> ACCOUNT_SCHEMA = TableSchema.fromBean(Account.class);

    private static final TableSchema<Reservation> RESERVATION_SCHEMA = TableSchema.fromBean(Reservation.class);

    private static final String TABLE_NAME = "test-table";

    @Mock
    private DynamoDbAsyncClient client;

    private LowLevelDynamoDbPaymentRepository repository;

    /** Instantiates the repository with the mock client and test table name. */
    @BeforeEach
    void setUp() {
        repository = new LowLevelDynamoDbPaymentRepository(client, TABLE_NAME);
    }

    @Test
    void getIdempotencyRecord_whenRecordExists_shouldUseStronglyConsistentRead() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        repository.getIdempotencyRecord("idem-key").join();

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void createPaymentTransaction_whenPaymentCreated_shouldCallTransactWriteItems() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.createPaymentTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildEvent("pay_1", 1),
                buildIdempotencyRecord("idem_1")
        ).join();

        verify(client).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void createPaymentTransaction_whenPaymentCreated_shouldApplyAttributeNotExistsOnlyOnIdempotencyPut() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.createPaymentTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildEvent("pay_1", 1),
                buildIdempotencyRecord("idem_1")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(CreatePaymentTransactItemOrder.values().length);
        assertThat(items.get(CreatePaymentTransactItemOrder.STREAM_HEAD.index()).put().conditionExpression()).isNull();
        assertThat(items.get(CreatePaymentTransactItemOrder.FIRST_EVENT.index()).put().conditionExpression()).isNull();
        assertThat(items.get(CreatePaymentTransactItemOrder.IDEMPOTENCY.index()).put().conditionExpression())
                .isEqualTo("attribute_not_exists(PK)");
    }

    @Test
    void queryPaymentPartition_whenHeadMissing_shouldReturnNull() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        PaymentPartitionQueryResult result = repository.queryPaymentPartition("pay_1").join();

        assertThat(result).isNull();
        verify(client).query(any(QueryRequest.class));
    }

    @Test
    void queryPaymentPartition_whenHeadFound_shouldCombineHeadAndSortedEvents() {
        PaymentStreamHead head = buildStreamHead("pay_1", 2, "FUNDS_RESERVED");
        PaymentEvent event2 = buildEvent("pay_1", 2);
        PaymentEvent event1 = buildEvent("pay_1", 1);

        List<Map<String, AttributeValue>> items = List.of(
                EVENT_SCHEMA.itemToMap(event2, false),
                STREAM_HEAD_SCHEMA.itemToMap(head, false),
                EVENT_SCHEMA.itemToMap(event1, false)
        );

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(items).build()));

        PaymentPartitionQueryResult result = repository.queryPaymentPartition("pay_1").join();

        assertThat(result.streamHead().getPaymentKey()).isEqualTo(head.getPaymentKey());
        assertThat(result.streamHead().getLastSequence()).isEqualTo(head.getLastSequence());
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().getFirst().getSequenceNumber()).isEqualTo(1);
        assertThat(result.events().get(1).getSequenceNumber()).isEqualTo(2);
    }

    @Test
    void queryAccountPartition_whenAccountMissing_shouldReturnNull() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        AccountPartitionQueryResult result = repository.queryAccountPartition("acc_1").join();

        assertThat(result).isNull();
        verify(client).query(any(QueryRequest.class));
    }

    @Test
    void queryAccountPartition_whenAccountFound_shouldCombineAccountAndSortedReservations() {
        Account account = buildAccount("acc_1");
        Reservation resB = buildReservation("acc_1", "res_b");
        Reservation resA = buildReservation("acc_1", "res_a");

        List<Map<String, AttributeValue>> items = List.of(
                RESERVATION_SCHEMA.itemToMap(resB, false),
                ACCOUNT_SCHEMA.itemToMap(account, false),
                RESERVATION_SCHEMA.itemToMap(resA, false)
        );

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(items).build()));

        AccountPartitionQueryResult result = repository.queryAccountPartition("acc_1").join();

        assertThat(result.account().getAccountKey()).isEqualTo(account.getAccountKey());
        assertThat(result.reservations()).hasSize(2);
        assertThat(result.reservations().getFirst().getReservationKey())
                .isLessThan(result.reservations().get(1).getReservationKey());
    }

    @Test
    void batchGetReservations_whenAllFound_shouldReturnReservations() {
        Reservation resA = buildReservation("acc_a", "res_a");
        Reservation resB = buildReservation("acc_a", "res_b");

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
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
        verify(client).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_whenPartialMissing_shouldReturnMissingIds() {
        Reservation resA = buildReservation("acc_a", "res_a");

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
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
    void batchGetReservations_whenDuplicateIdsProvided_shouldDeduplicateIdsBeforeBatchGet() {
        Reservation resA = buildReservation("acc_a", "res_a");

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        BatchGetItemResponse.builder()
                                .responses(Map.of(TABLE_NAME, List.of(
                                        RESERVATION_SCHEMA.itemToMap(resA, false))))
                                .build()));

        repository.batchGetReservations("acc_a", List.of("res_a", "res_a")).join();

        ArgumentCaptor<BatchGetItemRequest> captor = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(client).batchGetItem(captor.capture());
        assertThat(captor.getValue().requestItems().get(TABLE_NAME).keys()).hasSize(1);
    }

    @Test
    void batchGetReservations_whenReservationIdsEmpty_shouldReturnImmediately() {
        BatchGetReservationsResult result = repository.batchGetReservations("acc_a", List.of()).join();

        assertThat(result.reservations()).isEmpty();
        assertThat(result.missingReservationIds()).isEmpty();
        verify(client, never()).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_whenUnprocessedKeysRemain_shouldRetryUnprocessedKeys() {
        Reservation resA = buildReservation("acc_a", "res_a");
        Reservation resB = buildReservation("acc_a", "res_b");

        String pk = Account.KEY_PREFIX + "acc_a";
        String skB = Reservation.KEY_PREFIX + "res_b";
        Map<String, AttributeValue> keyBMap = Map.of(
                "PK", AttributeValue.builder().s(pk).build(),
                "SK", AttributeValue.builder().s(skB).build());

        BatchGetItemResponse firstResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(RESERVATION_SCHEMA.itemToMap(resA, false))))
                .unprocessedKeys(Map.of(TABLE_NAME,
                        KeysAndAttributes.builder().keys(List.of(keyBMap)).build()))
                .build();
        BatchGetItemResponse secondResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(RESERVATION_SCHEMA.itemToMap(resB, false))))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(firstResponse),
                        CompletableFuture.completedFuture(secondResponse));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_b")).join();

        assertThat(result.reservations()).hasSize(2);
        assertThat(result.missingReservationIds()).isEmpty();
        verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void batchGetReservations_whenMaxRetryAttemptsReached_shouldStopRetrying() {
        Reservation resA = buildReservation("acc_a", "res_a");

        String pk = Account.KEY_PREFIX + "acc_a";
        String skB = Reservation.KEY_PREFIX + "res_b";
        Map<String, AttributeValue> keyBMap = Map.of(
                "PK", AttributeValue.builder().s(pk).build(),
                "SK", AttributeValue.builder().s(skB).build());

        BatchGetItemResponse throttledResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE_NAME, List.of(RESERVATION_SCHEMA.itemToMap(resA, false))))
                .unprocessedKeys(Map.of(TABLE_NAME,
                        KeysAndAttributes.builder().keys(List.of(keyBMap)).build()))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(throttledResponse));

        BatchGetReservationsResult result = repository.batchGetReservations("acc_a",
                List.of("res_a", "res_b")).join();

        assertThat(result.reservations()).hasSize(1);
        assertThat(result.reservations().getFirst().getReservationId()).isEqualTo("res_a");
        assertThat(result.missingReservationIds()).containsExactly("res_b");
        int expectedBatchGetCalls = BatchGetItemHelper.MAX_UNPROCESSED_RETRIES + 1;
        verify(client, times(expectedBatchGetCalls)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void reserveFundsTransaction_whenFundsReserved_shouldPutAuditAndTemporaryReservation() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.reserveFundsTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildTemporaryReservation("acc_1", "res_pay_1"),
                buildEvent("pay_1", 2),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());

        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(5);
        assertThat(items.get(0).put().item()).containsEntry(
                "SK", AttributeValue.builder().s(Reservation.KEY_PREFIX + "res_pay_1").build());
        assertThat(items.get(1).put().item())
                .containsEntry("SK", AttributeValue.builder().s(Reservation.TEMPORARY_KEY_PREFIX + "res_pay_1").build())
                .containsKey("ttl");
    }

    @Test
    void completeFundsTransaction_whenFundsCompleted_shouldCallTransactWriteItems() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.completeFundsTransaction(
                buildStreamHead("pay_1", 2, "FUNDS_RESERVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildTemporaryReservation("acc_1", "res_pay_1"),
                buildLedgerEntry("acc_1", "pay_1"),
                buildEvent("pay_1", 3),
                new BigDecimal("100")
        ).join();

        verify(client).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void completeFundsTransaction_whenFundsCompleted_shouldAddAccountVersionCondition() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.completeFundsTransaction(
                buildStreamHead("pay_1", 2, "FUNDS_RESERVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildTemporaryReservation("acc_1", "res_pay_1"),
                buildLedgerEntry("acc_1", "pay_1"),
                buildEvent("pay_1", 3),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());

        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(6);
        assertThat(items.getFirst().update().conditionExpression()).isEqualTo("version = :expectedVersion");
        assertThat(items.getFirst().update().expressionAttributeValues())
                .containsEntry(":expectedVersion", AttributeValue.builder().n("1").build());
        // The temporary reservation row is deleted in the same transaction so settlement removes its own timer.
        assertThat(items.get(2).delete()).isNotNull();
        assertThat(items.get(2).delete().key())
                .containsEntry("SK", AttributeValue.builder().s(Reservation.TEMPORARY_KEY_PREFIX + "res_pay_1").build());
    }

    @Test
    void completeFundsTransaction_whenBuildingReservationUpdate_shouldConditionOnActiveAndExpiresAtGreaterThanNow() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.completeFundsTransaction(
                buildStreamHead("pay_1", 2, "FUNDS_RESERVED"),
                buildAccount("acc_1"),
                buildReservation("acc_1", "res_pay_1"),
                buildTemporaryReservation("acc_1", "res_pay_1"),
                buildLedgerEntry("acc_1", "pay_1"),
                buildEvent("pay_1", 3),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());

        // Item order: account update, reservation consume update, temporary reservation delete, ledger put, head update, event put.
        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items.get(1).update().conditionExpression()).isEqualTo("#s = :expectedStatus AND #e > :now");
        assertThat(items.get(1).update().expressionAttributeNames())
                .containsEntry("#s", "status")
                .containsEntry("#e", "expiresAt");
        assertThat(items.get(1).update().expressionAttributeValues())
                .containsEntry(":newStatus", AttributeValue.builder().s(ReservationStatus.CONSUMED.name()).build())
                .containsEntry(":expectedStatus", AttributeValue.builder().s(ReservationStatus.ACTIVE.name()).build())
                .containsKey(":now");
        assertThat(items.get(1).update().expressionAttributeValues().get(":now").n()).isNotBlank();
    }

    @Test
    void getReservation_whenRowExists_shouldReadAuditRowConsistently() {
        Reservation audit = buildReservation("acc_1", "res_a");
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder()
                        .item(RESERVATION_SCHEMA.itemToMap(audit, false))
                        .build()));

        Reservation result = repository.getReservation("acc_1", "res_a").join();

        assertThat(result).isNotNull();
        assertThat(result.getReservationId()).isEqualTo("res_a");
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.ACTIVE.name());

        ArgumentCaptor<GetItemRequest> captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().key())
                .containsEntry("PK", AttributeValue.builder().s(Account.KEY_PREFIX + "acc_1").build())
                .containsEntry("SK", AttributeValue.builder().s(Reservation.KEY_PREFIX + "res_a").build());
    }

    @Test
    void getReservation_whenRowMissing_shouldReturnNull() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        Reservation result = repository.getReservation("acc_1", "res_missing").join();

        assertThat(result).isNull();
    }

    @Test
    void releaseReservationTransaction_whenReservationReleased_shouldBuildReleaseTransactItems() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.releaseReservationTransaction(
                buildReservation("acc_1", "res_pay_1"),
                buildAccount("acc_1"),
                new BigDecimal("100")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());

        List<TransactWriteItem> items = captor.getValue().transactItems();
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().update().updateExpression()).isEqualTo("SET #s = :newStatus");
        assertThat(items.getFirst().update().conditionExpression()).isEqualTo("#s = :expectedStatus");
        assertThat(items.getFirst().update().expressionAttributeValues())
                .containsEntry(":newStatus", AttributeValue.builder().s(ReservationStatus.RELEASED.name()).build())
                .containsEntry(":expectedStatus", AttributeValue.builder().s(ReservationStatus.ACTIVE.name()).build())
                .doesNotContainKey(":now");
        assertThat(items.get(1).update().updateExpression())
                .isEqualTo("SET availableBalance = availableBalance + :amt, version = version + :one");
        assertThat(items.get(1).update().conditionExpression()).isEqualTo("version = :expectedVersion");
        assertThat(items.get(1).update().expressionAttributeValues())
                .containsEntry(":amt", AttributeValue.builder().n("100").build())
                .containsEntry(":expectedVersion", AttributeValue.builder().n("1").build());
    }

    @Test
    void queryMerchantPayments_whenPaginating_shouldReturnNextTokenAndReuseItAsExclusiveStartKey() {
        PaymentStreamHead head = buildStreamHead("pay_1", 1, "RECEIVED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:15:30Z").build(),
                "paymentId", AttributeValue.builder().s("pay_1").build());

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                                .items(List.of(STREAM_HEAD_SCHEMA.itemToMap(head, false)))
                                .lastEvaluatedKey(lastEvaluatedKey)
                                .build()),
                        CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build()));

        MerchantPaymentQueryResult firstPage = repository
                .queryMerchantPayments("merch_1", 1, false, null)
                .join();

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();

        repository.queryMerchantPayments("merch_1", 1, false, firstPage.nextToken()).join();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(captor.capture());
        List<QueryRequest> requests = captor.getAllValues();
        assertThat(requests.getFirst().exclusiveStartKey()).isEmpty();
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(lastEvaluatedKey);
    }

    @Test
    void queryMerchantPaymentsByState_whenPaginating_shouldReturnNextTokenAndReuseItAsExclusiveStartKey() {
        PaymentStreamHead head = buildStreamHead("pay_2", 2, "COMPLETED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "aggregateState", AttributeValue.builder().s("COMPLETED").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:16:00Z").build());

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                                .items(List.of(STREAM_HEAD_SCHEMA.itemToMap(head, false)))
                                .lastEvaluatedKey(lastEvaluatedKey)
                                .build()),
                        CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build()));

        MerchantPaymentQueryResult firstPage = repository
                .queryMerchantPaymentsByState("merch_1", "COMPLETED", 1, true, null)
                .join();

        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextToken()).isNotBlank();

        repository.queryMerchantPaymentsByState("merch_1", "COMPLETED", 1, true, firstPage.nextToken()).join();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(captor.capture());
        List<QueryRequest> requests = captor.getAllValues();
        assertThat(requests.getFirst().exclusiveStartKey()).isEmpty();
        assertThat(requests.get(1).exclusiveStartKey()).isEqualTo(lastEvaluatedKey);
        assertThat(requests.get(1).scanIndexForward()).isTrue();
    }

    @Test
    void queryMerchantPayments_whenNextTokenInvalid_shouldThrow() {
        assertThatThrownBy(() -> repository.queryMerchantPayments("merch_1", 1, false, "bad-token").join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void queryMerchantPaymentsByState_whenNextTokenInvalid_shouldThrow() {
        assertThatThrownBy(() -> repository.queryMerchantPaymentsByState(
                "merch_1", "COMPLETED", 1, false, "bad-token").join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void queryMerchantPayments_whenTokenFromAnotherMerchant_shouldThrow() {
        PaymentStreamHead head = buildStreamHead("pay_1", 1, "RECEIVED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:15:30Z").build(),
                "paymentId", AttributeValue.builder().s("pay_1").build());

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                        .items(List.of(STREAM_HEAD_SCHEMA.itemToMap(head, false)))
                        .lastEvaluatedKey(lastEvaluatedKey)
                        .build()));

        String tokenForMerch1 = repository.queryMerchantPayments("merch_1", 1, false, null).join().nextToken();

        // A token issued for merch_1 must not paginate merch_2. Reject as 400 rather than letting DynamoDB 500.
        assertThatThrownBy(() -> repository.queryMerchantPayments("merch_2", 1, false, tokenForMerch1).join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void queryMerchantPaymentsByState_whenTokenFromAnotherMerchant_shouldThrow() {
        PaymentStreamHead head = buildStreamHead("pay_2", 2, "COMPLETED");
        Map<String, AttributeValue> lastEvaluatedKey = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "aggregateState", AttributeValue.builder().s("COMPLETED").build(),
                "createdAtUtc", AttributeValue.builder().s("2026-03-18T10:16:00Z").build());

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder()
                        .items(List.of(STREAM_HEAD_SCHEMA.itemToMap(head, false)))
                        .lastEvaluatedKey(lastEvaluatedKey)
                        .build()));

        String tokenForMerch1 = repository
                .queryMerchantPaymentsByState("merch_1", "COMPLETED", 1, true, null).join().nextToken();

        assertThatThrownBy(() -> repository
                .queryMerchantPaymentsByState("merch_2", "COMPLETED", 1, true, tokenForMerch1).join())
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token");
    }

    @Test
    void rejectPaymentTransaction_whenPaymentRejected_shouldCallTransactWriteItems() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.rejectPaymentTransaction(
                buildStreamHead("pay_1", 1, "RECEIVED"),
                buildEvent("pay_1", 2),
                "INSUFFICIENT_FUNDS"
        ).join();

        verify(client).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    /** Builds a minimal payment stream head for transactional and query tests. */
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

    /** Builds a payment event with the given payment id and sequence number. */
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

    /** Builds an idempotency record keyed by the given idempotency key suffix. */
    private static IdempotencyRecord buildIdempotencyRecord(String key) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordKey(IdempotencyRecord.KEY_PREFIX + key);
        record.setEntityKey(IdempotencyRecord.ENTITY_TYPE);
        record.setEntityType(IdempotencyRecord.ENTITY_TYPE);
        record.setRequestHash("sha256-test");
        record.setCreatedAtUtc(Instant.now());
        return record;
    }

    /** Builds an active account with default USD balances for transaction tests. */
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

    /** Builds an active audit reservation under the given account. */
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

    /** Builds a temporary reservation under the given account with the table ttl attribute set. */
    private static Reservation buildTemporaryReservation(String accountId, String reservationId) {
        Reservation temporaryReservation = new Reservation();
        temporaryReservation.setAccountKey(Account.KEY_PREFIX + accountId);
        temporaryReservation.setReservationKey(Reservation.TEMPORARY_KEY_PREFIX + reservationId);
        temporaryReservation.setEntityType(Reservation.TEMPORARY_ENTITY_TYPE);
        temporaryReservation.setReservationId(reservationId);
        temporaryReservation.setPaymentId("pay_test");
        temporaryReservation.setAmount(new BigDecimal("100"));
        temporaryReservation.setStatus(ReservationStatus.ACTIVE.name());
        temporaryReservation.setCreatedAtUtc(Instant.now());
        temporaryReservation.setTtl(Instant.now().getEpochSecond() + 900);
        return temporaryReservation;
    }

    /** Builds a debit ledger entry tied to the given account and payment. */
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
