package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Unit tests for {@link DynamoDbStreamsPaymentEventListener}: stream record handling, lifecycle,
 * shard iterator helpers, and {@link DynamoDbStreamsAsyncClient} paths ({@code getRecordsWithRenewal},
 * {@code openShardIterator}).
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class DynamoDbStreamsPaymentEventListenerTest {

    private static final String STREAM_ARN =
            "arn:aws:dynamodb:eu-west-1:123456789012:table/T/stream/2024-01-01T00:00:00.000";

    private static final String SHARD_ID = "shardId-000000000000";

    @Mock
    private DynamoDbAsyncClient dynamoDbClient;

    @Mock
    private DynamoDbStreamsAsyncClient streamsClient;

    @Mock
    private OutboundPaymentProcessor processor;

    private DynamoDbStreamsPaymentEventListener listener;

    /** Constructs the listener with test table name and LATEST shard iterator type. */
    @BeforeEach
    void setUp() {
        listener = new DynamoDbStreamsPaymentEventListener(
                dynamoDbClient, streamsClient, processor, "TestTable", "LATEST");
    }

    @Test
    void lifecycle_whenStartedAndStopped_shouldTransitionPhases() {
        assertThat(listener.isRunning()).isFalse();
        assertThat(listener.getPhase()).isEqualTo(Integer.MAX_VALUE);

        listener.start();
        assertThat(listener.isRunning()).isTrue();

        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void processStreamRecord_whenCreatedEventInserted_shouldInvokeProcessor() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.builder().s(PaymentEvent.ENTITY_TYPE).build(),
                                "eventType", AttributeValue.builder().s(
                                        PaymentEventType.OUTBOUND_PAYMENT_CREATED.name()).build(),
                                "paymentId", AttributeValue.builder().s("pay_stream_1").build()))
                        .build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor).processPayment("pay_stream_1");
    }

    @Test
    void processStreamRecord_whenRecordModified_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.MODIFY)
                .dynamodb(StreamRecord.builder().newImage(Map.of()).build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void processStreamRecord_whenNonPaymentEventEntity_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.builder().s("OTHER").build(),
                                "paymentId", AttributeValue.builder().s("pay_x").build()))
                        .build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void processStreamRecord_whenNewImageNull_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder().newImage(null).build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void processStreamRecord_whenEntityTypeMissing_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "eventType", AttributeValue.builder().s(
                                        PaymentEventType.OUTBOUND_PAYMENT_CREATED.name()).build(),
                                "paymentId", AttributeValue.builder().s("pay_x").build()))
                        .build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void processStreamRecord_whenWrongEventType_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.builder().s(PaymentEvent.ENTITY_TYPE).build(),
                                "eventType", AttributeValue.builder().s(PaymentEventType.FUNDS_RESERVED.name()).build(),
                                "paymentId", AttributeValue.builder().s("pay_wrong_evt").build()))
                        .build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void processStreamRecord_whenPaymentIdMissing_shouldIgnore() {
        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.builder().s(PaymentEvent.ENTITY_TYPE).build(),
                                "eventType", AttributeValue.builder().s(
                                        PaymentEventType.OUTBOUND_PAYMENT_CREATED.name()).build()))
                        .build())
                .build();

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, never()).processPayment(anyString());
    }

    @Test
    void parseShardIteratorType_whenNullBlankUnknownOrValid_shouldParseCorrectly() {
        assertThat((ShardIteratorType) ReflectionTestUtils.invokeMethod(
                DynamoDbStreamsPaymentEventListener.class, "parseShardIteratorType", (String) null))
                .isEqualTo(ShardIteratorType.LATEST);
        assertThat((ShardIteratorType) ReflectionTestUtils.invokeMethod(
                DynamoDbStreamsPaymentEventListener.class, "parseShardIteratorType", "   "))
                .isEqualTo(ShardIteratorType.LATEST);
        assertThat((ShardIteratorType) ReflectionTestUtils.invokeMethod(
                DynamoDbStreamsPaymentEventListener.class, "parseShardIteratorType", "NOT_A_REAL_ITERATOR"))
                .isEqualTo(ShardIteratorType.LATEST);
        assertThat((ShardIteratorType) ReflectionTestUtils.invokeMethod(
                DynamoDbStreamsPaymentEventListener.class, "parseShardIteratorType", "trim_horizon"))
                .isEqualTo(ShardIteratorType.TRIM_HORIZON);
        assertThat((ShardIteratorType) ReflectionTestUtils.invokeMethod(
                DynamoDbStreamsPaymentEventListener.class, "parseShardIteratorType", "LATEST"))
                .isEqualTo(ShardIteratorType.LATEST);
    }

    @Test
    void processStreamRecord_whenProcessorThrows_shouldPropagateUnderRetryLimit() {
        Record record = buildCreatedRecord("pay_err");

        doThrow(new RuntimeException("fail"))
                .when(processor).processPayment("pay_err");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fail");

        verify(processor).processPayment("pay_err");
    }

    @Test
    void processStreamRecord_whenProcessorExhaustsRetries_shouldSwallowAndUnblockShard() {
        Record record = buildCreatedRecord("pay_poison");

        doThrow(new RuntimeException("permanent failure"))
                .when(processor).processPayment("pay_poison");

        for (int i = 1; i < DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES; i++) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                    .isInstanceOf(RuntimeException.class);
        }

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .doesNotThrowAnyException();

        verify(processor, times(DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES))
                .processPayment("pay_poison");

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> counts =
                (ConcurrentHashMap<String, Integer>) ReflectionTestUtils.getField(listener, "retryCounts");
        assertThat(counts).doesNotContainKey("pay_poison");
    }

    @Test
    void processStreamRecord_whenSuccessAfterFailure_shouldClearRetryCount() {
        Record record = buildCreatedRecord("pay_retry_ok");

        doThrow(new RuntimeException("transient"))
                .doNothing()
                .when(processor).processPayment("pay_retry_ok");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .isInstanceOf(RuntimeException.class);

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, times(2)).processPayment("pay_retry_ok");

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Integer> counts =
                (ConcurrentHashMap<String, Integer>) ReflectionTestUtils.getField(listener, "retryCounts");
        assertThat(counts).doesNotContainKey("pay_retry_ok");
    }

    @Test
    void discoverStreamArn_whenTableDescribeFails_shouldReturnNull() {
        when(dynamoDbClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no table")));

        String arn = ReflectionTestUtils.invokeMethod(listener, "discoverStreamArn");
        assertThat(arn).isNull();
    }

    @Test
    void getRecordsWithRenewal_whenIteratorExpired_shouldOpenFreshIteratorAndRetryGetRecords() throws Exception {
        GetRecordsResponse second = GetRecordsResponse.builder()
                .records(List.of())
                .nextShardIterator("next-after-retry")
                .build();

        when(streamsClient.getRecords(any(GetRecordsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(ExpiredIteratorException.builder().build())))
                .thenReturn(CompletableFuture.completedFuture(second));

        when(streamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetShardIteratorResponse.builder().shardIterator("fresh-iterator").build()));

        Object cp = newShardCheckpoint();
        ReflectionTestUtils.setField(cp, "lastSequenceNumber", "111");

        GetRecordsResponse result = ReflectionTestUtils.invokeMethod(
                listener,
                "getRecordsWithRenewal",
                STREAM_ARN,
                SHARD_ID,
                cp,
                "stale-iterator");

        assertThat(result).isSameAs(second);
        assertThat(ReflectionTestUtils.getField(cp, "nextIterator")).isEqualTo("fresh-iterator");
        verify(streamsClient).getShardIterator(any(GetShardIteratorRequest.class));
    }

    @Test
    void getRecordsWithRenewal_whenNonExpiredCompletionException_shouldRethrow() throws Exception {
        when(streamsClient.getRecords(any(GetRecordsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(new RuntimeException("throttle"))));

        Object cp = newShardCheckpoint();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                listener,
                "getRecordsWithRenewal",
                STREAM_ARN,
                SHARD_ID,
                cp,
                "any"))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("throttle");
    }

    @Test
    void openShardIterator_whenTrimmedAfterSequence_shouldClearSequenceAndFallBackToConfiguredIterator() throws Exception {
        when(streamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(TrimmedDataAccessException.builder().build())))
                .thenReturn(CompletableFuture.completedFuture(
                        GetShardIteratorResponse.builder().shardIterator("fallback-iter").build()));

        Object cp = newShardCheckpoint();
        ReflectionTestUtils.setField(cp, "lastSequenceNumber", "999");

        String iterator = ReflectionTestUtils.invokeMethod(
                listener, "openShardIterator", STREAM_ARN, SHARD_ID, cp);

        assertThat(iterator).isEqualTo("fallback-iter");
        assertThat(ReflectionTestUtils.getField(cp, "lastSequenceNumber")).isNull();
        assertThat(ReflectionTestUtils.getField(cp, "nextIterator")).isEqualTo("fallback-iter");
    }

    /** Builds an INSERT stream record for an OUTBOUND_PAYMENT_CREATED payment event. */
    private static Record buildCreatedRecord(String paymentId) {
        return Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.builder().s(PaymentEvent.ENTITY_TYPE).build(),
                                "eventType", AttributeValue.builder().s(
                                        PaymentEventType.OUTBOUND_PAYMENT_CREATED.name()).build(),
                                "paymentId", AttributeValue.builder().s(paymentId).build()))
                        .build())
                .build();
    }

    /** Reflectively instantiates the listener package-private {@code ShardCheckpoint} type. */
    private static Object newShardCheckpoint() throws Exception {
        Class<?> inner = Class.forName(
                DynamoDbStreamsPaymentEventListener.class.getName() + "$ShardCheckpoint");
        Constructor<?> ctor = inner.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }
}
