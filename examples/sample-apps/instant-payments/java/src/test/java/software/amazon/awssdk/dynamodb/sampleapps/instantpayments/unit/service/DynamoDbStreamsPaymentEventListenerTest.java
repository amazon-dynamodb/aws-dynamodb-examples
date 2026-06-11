package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
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
        when(processor.processPayment("pay_stream_1"))
                .thenReturn(CompletableFuture.completedFuture(null));

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

        when(processor.processPayment("pay_err"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("fail")));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("fail");

        verify(processor).processPayment("pay_err");
    }

    @Test
    void processStreamRecord_whenProcessorExhaustsRetries_shouldSwallowAndUnblockShard() {
        Record record = buildCreatedRecord("pay_poison");

        when(processor.processPayment("pay_poison"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("permanent failure")));

        for (int i = 1; i < DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES; i++) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                    .isInstanceOf(CompletionException.class);
        }

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .doesNotThrowAnyException();

        verify(processor, times(DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES))
                .processPayment("pay_poison");

        @SuppressWarnings("unchecked")
        Map<String, Integer> counts =
                (Map<String, Integer>) ReflectionTestUtils.getField(listener, "processingFailureCounts");
        assertThat(counts).doesNotContainKey("pay_poison");
    }

    @Test
    void processStreamRecord_whenSuccessAfterFailure_shouldClearRetryCount() {
        Record record = buildCreatedRecord("pay_retry_ok");

        when(processor.processPayment("pay_retry_ok"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("transient")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record))
                .isInstanceOf(CompletionException.class);

        ReflectionTestUtils.invokeMethod(listener, "processStreamRecord", record);

        verify(processor, times(2)).processPayment("pay_retry_ok");

        @SuppressWarnings("unchecked")
        Map<String, Integer> counts =
                (Map<String, Integer>) ReflectionTestUtils.getField(listener, "processingFailureCounts");
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

    @Test
    void discoverShards_whenStreamHasMultiplePages_shouldWalkAllPagesUntilLastEvaluatedShardIdIsNull() {
        Shard shardPage1 = Shard.builder().shardId("shardId-000000000001").build();
        Shard shardPage2 = Shard.builder().shardId("shardId-000000000002").build();

        DescribeStreamResponse page1 = DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(shardPage1)
                        .lastEvaluatedShardId("shardId-000000000001")
                        .build())
                .build();
        DescribeStreamResponse page2 = DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(shardPage2)
                        .lastEvaluatedShardId(null)
                        .build())
                .build();

        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(page1))
                .thenReturn(CompletableFuture.completedFuture(page2));

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).containsExactly(shardPage1, shardPage2);

        ArgumentCaptor<DescribeStreamRequest> captor = ArgumentCaptor.forClass(DescribeStreamRequest.class);
        verify(streamsClient, times(2)).describeStream(captor.capture());
        assertThat(captor.getAllValues().get(0).exclusiveStartShardId()).isNull();
        assertThat(captor.getAllValues().get(1).exclusiveStartShardId()).isEqualTo("shardId-000000000001");
    }

    @Test
    void discoverShards_whenSinglePage_shouldStopAfterFirstDescribeStream() {
        Shard shard = Shard.builder().shardId(SHARD_ID).build();
        DescribeStreamResponse onePage = DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(shard)
                        .lastEvaluatedShardId(null)
                        .build())
                .build();

        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(onePage));

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).containsExactly(shard);
        ArgumentCaptor<DescribeStreamRequest> captor = ArgumentCaptor.forClass(DescribeStreamRequest.class);
        verify(streamsClient, times(1)).describeStream(captor.capture());
        assertThat(captor.getValue().exclusiveStartShardId()).isNull();
    }

    @Test
    void discoverShards_whenLastEvaluatedShardIdBlank_shouldStopWithoutRequestingNextPage() {
        Shard shard = Shard.builder().shardId(SHARD_ID).build();
        DescribeStreamResponse blankToken = DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(shard)
                        .lastEvaluatedShardId("")
                        .build())
                .build();

        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(blankToken));

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).containsExactly(shard);
        verify(streamsClient, times(1)).describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    void discoverShards_whenDescribeStreamFails_shouldReturnEmptyList() {
        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no stream")));

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).isEmpty();
    }

    @Test
    void discoverShards_whenShardClosed_shouldPruneStaleCheckpoint() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpoints =
                (Map<String, Object>) ReflectionTestUtils.getField(listener, "checkpoints");
        checkpoints.put("shardId-closed-old", newShardCheckpoint());
        checkpoints.put(SHARD_ID, newShardCheckpoint());

        Shard activeShard = Shard.builder().shardId(SHARD_ID).build();
        DescribeStreamResponse onePage = DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(activeShard)
                        .lastEvaluatedShardId(null)
                        .build())
                .build();
        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(onePage));

        ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        // The closed shard's checkpoint is dropped. Only the still-active shard remains.
        assertThat(checkpoints).containsOnlyKeys(SHARD_ID);
    }

    @Test
    void discoverShards_whenDescribeStreamFails_shouldNotPruneCheckpoints() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> checkpoints =
                (Map<String, Object>) ReflectionTestUtils.getField(listener, "checkpoints");
        checkpoints.put(SHARD_ID, newShardCheckpoint());

        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no stream")));

        ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        // A transient describe failure must not wipe live checkpoints.
        assertThat(checkpoints).containsKey(SHARD_ID);
    }

    @Test
    void processingFailureCounts_whenManyTransientFailuresNeverReturn_shouldStayBoundedAtCap() throws Exception {
        int cap = maxRetryCountEntries();

        @SuppressWarnings("unchecked")
        Map<String, Integer> processingFailureCounts =
                (Map<String, Integer>) ReflectionTestUtils.getField(listener, "processingFailureCounts");

        // Simulate distinct ids that each failed once and never reappeared (so they never self-remove).
        int overflow = cap + 50;
        for (int i = 0; i < overflow; i++) {
            processingFailureCounts.merge("pay_transient_" + i, 1, Integer::sum);
        }

        // The access-ordered LRU evicts least-recently-touched entries, holding the map at the cap.
        assertThat(processingFailureCounts).hasSize(cap);
    }

    @Test
    void pollLimit_whenConfigured_shouldNotExceedGetRecordsHardCap() throws Exception {
        // DynamoDB Streams GetRecords accepts at most 1000 records per call. POLL_LIMIT must respect it.
        int pollLimit = intConstant("POLL_LIMIT");
        assertThat(pollLimit).isPositive();
        assertThat(pollLimit).isLessThanOrEqualTo(1000);
    }

    @Test
    void maxGetRecordsRoundsPerShard_whenConfigured_shouldBePositive() throws Exception {
        // Per-tick round cap that keeps one hot shard from starving others. It must be a positive bound.
        assertThat(intConstant("MAX_GET_RECORDS_ROUNDS_PER_SHARD")).isPositive();
    }

    @Test
    void maxProcessRetries_whenConfigured_shouldBePositive() {
        assertThat(DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES).isPositive();
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

    /** Reflectively reads a private {@code int} constant by field name. */
    private static int intConstant(String fieldName) throws Exception {
        Field field = DynamoDbStreamsPaymentEventListener.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    /** Reflectively instantiates the listener package-private {@code ShardCheckpoint} type. */
    private static Object newShardCheckpoint() throws Exception {
        Class<?> inner = Class.forName(
                DynamoDbStreamsPaymentEventListener.class.getName() + "$ShardCheckpoint");
        Constructor<?> ctor = inner.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /** Reflectively reads the private {@code MAX_RETRY_COUNT_ENTRIES} cap. */
    private static int maxRetryCountEntries() throws Exception {
        Field field = DynamoDbStreamsPaymentEventListener.class.getDeclaredField("MAX_RETRY_COUNT_ENTRIES");
        field.setAccessible(true);
        return field.getInt(null);
    }
}
