package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
}
