package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.service;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.DynamoDbStreamsLeaderboardListener;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link DynamoDbStreamsLeaderboardListener}.
 *
 * <p>Verifies leaderboard projection reacts to synthetic stream payloads using the wired repository.
 *
 * <p>Tagged {@code integration} and {@code smoke} so the stream-driven leaderboard path is
 * exercised when running either group ({@code -Dgroups=smoke} or {@code -Dgroups=integration}).
 */
@Tag("integration")
@Tag("smoke")
class DynamoDbStreamsLeaderboardListenerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DynamoDbStreamsLeaderboardListener dynamoDbStreamsLeaderboardListener;

    @Test
    void processStreamRecord_whenPvpMatchInserted_shouldPopulateLeaderboard() throws Exception {
        String playerId = "stream-integ-player-one";
        int scoreFor = 42_000;

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .sequenceNumber("integ-seq-stream-001")
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PVP_MATCH.name()),
                                "playerId", AttributeValue.fromS(playerId),
                                "playerName", AttributeValue.fromS("StreamingHero"),
                                "playerScore", AttributeValue.fromN(String.valueOf(scoreFor))))
                        .build())
                .build();

        dynamoDbStreamsLeaderboardListener.processStreamRecord(record);

        performAsync(mockMvc, get("/api/v1/leaderboards/{scope}", DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].playerId").value(playerId))
                .andExpect(jsonPath("$.entries[0].playerName").value("StreamingHero"))
                .andExpect(jsonPath("$.entries[0].score").value(scoreFor))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }

    @Test
    void processStreamRecord_whenNonPvpMatchInserted_shouldNotPopulateLeaderboard() throws Exception {
        String playerId = "stream-integ-purchase-player";

        Record record = Record.builder()
                .eventName(OperationType.INSERT)
                .dynamodb(StreamRecord.builder()
                        .sequenceNumber("integ-seq-stream-purchase-001")
                        .newImage(Map.of(
                                "entityType", AttributeValue.fromS(GameEvent.ENTITY_TYPE),
                                "eventType", AttributeValue.fromS(GameEventType.PURCHASE.name()),
                                "playerId", AttributeValue.fromS(playerId),
                                "playerName", AttributeValue.fromS("Shopper"),
                                "playerScore", AttributeValue.fromN("99999")))
                        .build())
                .build();

        dynamoDbStreamsLeaderboardListener.processStreamRecord(record);

        // The purchase player must never be projected onto the leaderboard.
        performAsync(mockMvc, get("/api/v1/leaderboards/{scope}", DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[?(@.playerId=='" + playerId + "')]").isEmpty());
    }
}
