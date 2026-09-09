package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.repository;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.CreatePaymentTransactItemOrder;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.LowLevelDynamoDbPaymentRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreatePaymentTransactItemOrder}: the shared enum that names the create payment transact
 * item order. These assertions are the contract that keeps the producers (repositories) and the
 * consumer ({@code OutboundPaymentService.isIdempotencyConflict}) aligned, so a future reordering that
 * forgets one end fails here instead of silently breaking idempotency-conflict detection.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class CreatePaymentTransactItemOrderTest {

    private static final String TABLE_NAME = "test-table";

    @Mock
    private DynamoDbAsyncClient client;

    private LowLevelDynamoDbPaymentRepository repository;

    /** Instantiates a producer with the mock client so the captured request reflects producer order. */
    @BeforeEach
    void setUp() {
        repository = new LowLevelDynamoDbPaymentRepository(client, TABLE_NAME);
    }

    @Test
    void index_whenDeclaredInOrder_shouldEqualOrdinal() {
        assertThat(CreatePaymentTransactItemOrder.STREAM_HEAD.index()).isEqualTo(0);
        assertThat(CreatePaymentTransactItemOrder.FIRST_EVENT.index()).isEqualTo(1);
        assertThat(CreatePaymentTransactItemOrder.IDEMPOTENCY.index()).isEqualTo(2);
        for (CreatePaymentTransactItemOrder item : CreatePaymentTransactItemOrder.values()) {
            assertThat(item.index()).isEqualTo(item.ordinal());
        }
    }

    @Test
    void createPaymentTransaction_whenItemsBuilt_shouldMatchEnumIndices() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        repository.createPaymentTransaction(
                buildStreamHead("pay_1"),
                buildEvent("pay_1"),
                buildIdempotencyRecord("idem_1")
        ).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        List<TransactWriteItem> items = captor.getValue().transactItems();

        assertThat(items).hasSize(CreatePaymentTransactItemOrder.values().length);
        // Only the idempotency put is conditional, so its position pins down the producer order.
        assertThat(items.get(CreatePaymentTransactItemOrder.STREAM_HEAD.index()).put().conditionExpression()).isNull();
        assertThat(items.get(CreatePaymentTransactItemOrder.FIRST_EVENT.index()).put().conditionExpression()).isNull();
        assertThat(items.get(CreatePaymentTransactItemOrder.IDEMPOTENCY.index()).put().conditionExpression())
                .isEqualTo("attribute_not_exists(PK)");
    }

    /** Minimal stream head for the create transact. */
    private PaymentStreamHead buildStreamHead(String paymentId) {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(0);
        head.setAggregateState("RECEIVED");
        head.setUpdatedAtUtc(Instant.now());
        head.setCreatedAtUtc(Instant.now());
        return head;
    }

    /** Minimal first domain event for the create transact. */
    private PaymentEvent buildEvent(String paymentId) {
        PaymentEvent event = new PaymentEvent();
        event.setPaymentKey(Payment.KEY_PREFIX + paymentId);
        event.setEventKey(PaymentEvent.sortKeyForSequence(1));
        event.setEntityType(PaymentEvent.ENTITY_TYPE);
        event.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        event.setSequenceNumber(1);
        event.setOccurredAt(Instant.now());
        return event;
    }

    /** Minimal idempotency record for the create transact. */
    private IdempotencyRecord buildIdempotencyRecord(String idempotencyKey) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyRecordKey(IdempotencyRecord.KEY_PREFIX + idempotencyKey);
        record.setEntityKey(IdempotencyRecord.ENTITY_TYPE);
        record.setEntityType(IdempotencyRecord.ENTITY_TYPE);
        record.setRequestHash("sha256-test");
        record.setCreatedAtUtc(Instant.now());
        return record;
    }
}


