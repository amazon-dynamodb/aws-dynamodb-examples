package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LeaderboardRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.DynamoDbStreamsLeaderboardListener;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DynamoDbStreamsLeaderboardListener} stream processing and poll helpers.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class DynamoDbStreamsLeaderboardListenerTest {

    private static final String STREAM_ARN =
            "arn:aws:dynamodb:eu-west-1:123456789012:table/JavaGamingGameEvents/stream/2024-01-01T00:00:00.000";
    private static final String SHARD_ID = "shardId-00000001778153554951-c17279f5";

    @Mock
    private DynamoDbAsyncClient dynamoDbClient;

    @Mock
    private DynamoDbStreamsAsyncClient streamsClient;

    @Mock
    private LeaderboardRepository leaderboardRepository;

    /**
     * Builds a listener with fixed table name and {@code LATEST} shard iterator type.
     *
     * @return configured listener using mock clients
     */
    private DynamoDbStreamsLeaderboardListener createListener() {
        return new DynamoDbStreamsLeaderboardListener(
                dynamoDbClient, streamsClient, leaderboardRepository,
                "JavaGamingGameEvents", "LATEST");
    }

    @Test
    void processStreamRecord_whenPvpMatchInserted_shouldWriteLeaderboardEntry() {
        when(leaderboardRepository.putLeaderboardEntry(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = buildPvpMatchRecord("player-1", "AlphaWolf", 2500);
        listener.processStreamRecord(record);

        ArgumentCaptor<LeaderboardEntry> captor = ArgumentCaptor.forClass(LeaderboardEntry.class);
        verify(leaderboardRepository).putLeaderboardEntry(captor.capture());

        LeaderboardEntry entry = captor.getValue();
        assertThat(entry.getPlayerId()).isEqualTo("player-1");
        assertThat(entry.getPlayerName()).isEqualTo("AlphaWolf");
        assertThat(entry.getScore()).isEqualTo(2500);
        assertThat(entry.getPartitionKey()).isEqualTo(
                LeaderboardEntry.buildPartitionKey(DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE));
        assertThat(entry.getSortKey()).isEqualTo(LeaderboardEntry.buildSortKey(2500, "player-1"));
        assertThat(entry.getEntityType()).isEqualTo(LeaderboardEntry.ENTITY_TYPE);
    }

    @Test
    void processStreamRecord_whenEventModified_shouldSkip() {
        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = Record.builder()
                .eventName(OperationType.MODIFY)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name())))
                        .build())
                .build();

        listener.processStreamRecord(record);

        verify(leaderboardRepository, never()).putLeaderboardEntry(any());
    }

    @Test
    void processStreamRecord_whenNonGameEventEntity_shouldSkip() {
        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS("OTHER_ENTITY"),
                                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name())))
                        .build())
                .build();

        listener.processStreamRecord(record);

        verify(leaderboardRepository, never()).putLeaderboardEntry(any());
    }

    @Test
    void processStreamRecord_whenNonPvpMatchEventType_shouldSkip() {
        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PURCHASE.name())))
                        .build())
                .build();

        listener.processStreamRecord(record);

        verify(leaderboardRepository, never()).putLeaderboardEntry(any());
    }

    @Test
    void processStreamRecord_whenScoreForMissing_shouldSkip() {
        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name()),
                                "playerId", AttributeValue.fromS("player-1")))
                        .sequenceNumber("seq-1")
                        .build())
                .build();

        listener.processStreamRecord(record);

        verify(leaderboardRepository, never()).putLeaderboardEntry(any());
    }

    @Test
    void processStreamRecord_whenPlayerNameMissing_shouldUsePlayerIdAsName() {
        when(leaderboardRepository.putLeaderboardEntry(any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        DynamoDbStreamsLeaderboardListener listener = createListener();

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name()),
                                "playerId", AttributeValue.fromS("player-1"),
                                "playerScore", AttributeValue.fromN("1500")))
                        .sequenceNumber("seq-1")
                        .build())
                .build();

        listener.processStreamRecord(record);

        ArgumentCaptor<LeaderboardEntry> captor = ArgumentCaptor.forClass(LeaderboardEntry.class);
        verify(leaderboardRepository).putLeaderboardEntry(captor.capture());
        assertThat(captor.getValue().getPlayerName()).isEqualTo("player-1");
    }

    @Test
    void getRecordsWithRenewal_whenTrimmedOnGetRecords_shouldClearSequenceAndRetry() throws Exception {
        GetRecordsResponse second = GetRecordsResponse.builder()
                .records(List.of())
                .nextShardIterator("next-after-trim-retry")
                .build();

        when(streamsClient.getRecords(any(GetRecordsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(TrimmedDataAccessException.builder().build())))
                .thenReturn(CompletableFuture.completedFuture(second));

        when(streamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetShardIteratorResponse.builder().shardIterator("fresh-after-trim").build()));

        DynamoDbStreamsLeaderboardListener listener = createListener();
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
        assertThat(ReflectionTestUtils.getField(cp, "lastSequenceNumber")).isNull();
        assertThat(ReflectionTestUtils.getField(cp, "nextIterator")).isEqualTo("fresh-after-trim");
        verify(streamsClient).getShardIterator(any(GetShardIteratorRequest.class));
    }

    @Test
    void openShardIterator_whenTrimmedAfterSequence_shouldClearSequenceAndFallBackToConfiguredIterator() throws Exception {
        when(streamsClient.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(TrimmedDataAccessException.builder().build())))
                .thenReturn(CompletableFuture.completedFuture(
                        GetShardIteratorResponse.builder().shardIterator("fallback-iter").build()));

        DynamoDbStreamsLeaderboardListener listener = createListener();
        Object cp = newShardCheckpoint();
        ReflectionTestUtils.setField(cp, "lastSequenceNumber", "999");

        String iterator = ReflectionTestUtils.invokeMethod(
                listener, "openShardIterator", STREAM_ARN, SHARD_ID, cp);

        assertThat(iterator).isEqualTo("fallback-iter");
        assertThat(ReflectionTestUtils.getField(cp, "lastSequenceNumber")).isNull();
        assertThat(ReflectionTestUtils.getField(cp, "nextIterator")).isEqualTo("fallback-iter");
    }

    @Test
    void getRecordsWithRenewal_whenNonRenewableCompletionException_shouldRethrow() throws Exception {
        when(streamsClient.getRecords(any(GetRecordsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(new RuntimeException("throttle"))));

        DynamoDbStreamsLeaderboardListener listener = createListener();
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
    void pollLimit_whenConfigured_shouldStayWithinGetRecordsMaximum() {
        int pollLimit = (Integer) ReflectionTestUtils.getField(
                DynamoDbStreamsLeaderboardListener.class, "POLL_LIMIT");
        assertThat(pollLimit).isPositive().isLessThanOrEqualTo(1000);
    }

    @Test
    void maxGetRecordsRoundsPerShard_whenConfigured_shouldBePositive() {
        int rounds = (Integer) ReflectionTestUtils.getField(
                DynamoDbStreamsLeaderboardListener.class, "MAX_GET_RECORDS_ROUNDS_PER_SHARD");
        assertThat(rounds).isPositive();
    }

    @Test
    void discoverShards_whenSinglePage_shouldReturnAllShardsInOneCall() {
        DescribeStreamResponse page = describeStreamResponse(List.of(shard("shard-A")), null);
        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(page));

        DynamoDbStreamsLeaderboardListener listener = createListener();
        markRunning(listener);

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).extracting(Shard::shardId).containsExactly("shard-A");
        verify(streamsClient, times(1)).describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    void discoverShards_whenMultiplePages_shouldPageWithExclusiveStartShardId() {
        DescribeStreamResponse page1 = describeStreamResponse(List.of(shard("shard-A")), "shard-A");
        DescribeStreamResponse page2 = describeStreamResponse(List.of(shard("shard-B")), null);
        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(page1))
                .thenReturn(CompletableFuture.completedFuture(page2));

        DynamoDbStreamsLeaderboardListener listener = createListener();
        markRunning(listener);

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).extracting(Shard::shardId).containsExactly("shard-A", "shard-B");

        ArgumentCaptor<DescribeStreamRequest> captor = ArgumentCaptor.forClass(DescribeStreamRequest.class);
        verify(streamsClient, times(2)).describeStream(captor.capture());
        assertThat(captor.getAllValues().get(0).exclusiveStartShardId()).isNull();
        assertThat(captor.getAllValues().get(1).exclusiveStartShardId()).isEqualTo("shard-A");
    }

    @Test
    void discoverShards_whenStreamEmpty_shouldReturnEmptyListInOneCall() {
        DescribeStreamResponse page = describeStreamResponse(List.of(), null);
        when(streamsClient.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(page));

        DynamoDbStreamsLeaderboardListener listener = createListener();
        markRunning(listener);

        List<Shard> shards = ReflectionTestUtils.invokeMethod(listener, "discoverShards", STREAM_ARN);

        assertThat(shards).isEmpty();
        verify(streamsClient, times(1)).describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    void pruneStaleCheckpoints_whenShardClosed_shouldDropOnlyMissingCheckpoint() throws Exception {
        DynamoDbStreamsLeaderboardListener listener = createListener();
        Map<String, Object> checkpoints = checkpointsOf(listener);
        checkpoints.put("shard-A", newShardCheckpoint());
        checkpoints.put("shard-B", newShardCheckpoint());
        checkpoints.put("shard-C", newShardCheckpoint());

        ReflectionTestUtils.invokeMethod(listener, "pruneStaleCheckpoints",
                List.of(shard("shard-A"), shard("shard-C")));

        assertThat(checkpoints.keySet()).containsExactlyInAnyOrder("shard-A", "shard-C");
    }

    @Test
    void pruneStaleCheckpoints_whenNoShards_shouldClearAllCheckpoints() throws Exception {
        DynamoDbStreamsLeaderboardListener listener = createListener();
        Map<String, Object> checkpoints = checkpointsOf(listener);
        checkpoints.put("shard-A", newShardCheckpoint());
        checkpoints.put("shard-B", newShardCheckpoint());

        ReflectionTestUtils.invokeMethod(listener, "pruneStaleCheckpoints", List.of());

        assertThat(checkpoints).isEmpty();
    }

    @Test
    void writeLeaderboardEntryWithRetry_whenManyDistinctFailures_shouldBoundRetryCountsToCapacity() {
        when(leaderboardRepository.putLeaderboardEntry(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));

        int capacity = (Integer) ReflectionTestUtils.getField(
                DynamoDbStreamsLeaderboardListener.class, "MAX_RETRY_COUNT_ENTRIES");

        DynamoDbStreamsLeaderboardListener listener = createListener();

        for (int i = 0; i <= capacity; i++) {
            Record record = buildPvpMatchRecord("player-" + i, "Name", 100);
            try {
                listener.processStreamRecord(record);
            } catch (RuntimeException ignored) {
                // first failure rethrows below the poison-pill limit, which is expected here
            }
        }

        Map<String, Integer> retryCounts = retryCountsOf(listener);
        assertThat(retryCounts).hasSize(capacity);
        assertThat(retryCounts).doesNotContainKey("player-0#seq-001");
    }

    @Test
    void writeLeaderboardEntryWithRetry_whenRecordLaterSucceeds_shouldRemoveRetryEntry() {
        when(leaderboardRepository.putLeaderboardEntry(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")))
                .thenReturn(CompletableFuture.completedFuture(null));

        DynamoDbStreamsLeaderboardListener listener = createListener();
        Record record = buildPvpMatchRecord("player-x", "Name", 100);

        try {
            listener.processStreamRecord(record);
        } catch (RuntimeException ignored) {
            // first failure rethrows below the poison-pill limit
        }

        Map<String, Integer> retryCounts = retryCountsOf(listener);
        assertThat(retryCounts).containsKey("player-x#seq-001");

        listener.processStreamRecord(record);

        assertThat(retryCounts).doesNotContainKey("player-x#seq-001");
    }

    /**
     * Builds a minimal INSERT stream {@link Record} for a PVP match with score and names populated.
     *
     * @param playerId owner id
     * @param playerName display name in the stream image
     * @param score score mapped to {@code scoreFor}
     * @return record suitable for {@link DynamoDbStreamsLeaderboardListener#processStreamRecord(Record)}
     */
    private Record buildPvpMatchRecord(String playerId, String playerName, int score) {
        Map<String, AttributeValue> newImage = Map.of(
                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name()),
                "playerId", AttributeValue.fromS(playerId),
                "playerName", AttributeValue.fromS(playerName),
                "playerScore", AttributeValue.fromN(String.valueOf(score)));

        return Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .newImage(newImage)
                        .sequenceNumber("seq-001")
                        .build())
                .build();
    }

    /**
     * Reflectively constructs the package-private {@code ShardCheckpoint} holder used by the listener.
     *
     * @return new checkpoint instance
     * @throws Exception if reflection fails
     */
    private static Object newShardCheckpoint() throws Exception {
        Class<?> inner = Class.forName(
                DynamoDbStreamsLeaderboardListener.class.getName() + "$ShardCheckpoint");
        Constructor<?> ctor = inner.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    /**
     * Builds a {@link DescribeStreamResponse} with the given shards and optional pagination marker.
     *
     * @param shards               shards to return on this page
     * @param lastEvaluatedShardId pagination marker, or {@code null} for the final page
     * @return populated describe-stream response
     */
    private static DescribeStreamResponse describeStreamResponse(List<Shard> shards, String lastEvaluatedShardId) {
        return DescribeStreamResponse.builder()
                .streamDescription(StreamDescription.builder()
                        .shards(shards)
                        .lastEvaluatedShardId(lastEvaluatedShardId)
                        .build())
                .build();
    }

    /**
     * Builds a {@link Shard} carrying only the shard id.
     *
     * @param shardId shard identifier
     * @return shard with the given id
     */
    private static Shard shard(String shardId) {
        return Shard.builder().shardId(shardId).build();
    }

    /**
     * Flips the listener's {@code running} flag so shard discovery pagination proceeds across pages.
     *
     * @param listener the listener under test
     */
    private static void markRunning(DynamoDbStreamsLeaderboardListener listener) {
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(listener, "running");
        running.set(true);
    }

    /**
     * Returns the listener's in-memory checkpoint map for direct seeding and assertions.
     *
     * @param listener the listener under test
     * @return the mutable checkpoints map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> checkpointsOf(DynamoDbStreamsLeaderboardListener listener) {
        return (Map<String, Object>) ReflectionTestUtils.getField(listener, "checkpoints");
    }

    /**
     * Returns the listener's bounded retry-count map for assertions.
     *
     * @param listener the listener under test
     * @return the retry-count map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> retryCountsOf(DynamoDbStreamsLeaderboardListener listener) {
        return (Map<String, Integer>) ReflectionTestUtils.getField(listener, "retryCounts");
    }
}
