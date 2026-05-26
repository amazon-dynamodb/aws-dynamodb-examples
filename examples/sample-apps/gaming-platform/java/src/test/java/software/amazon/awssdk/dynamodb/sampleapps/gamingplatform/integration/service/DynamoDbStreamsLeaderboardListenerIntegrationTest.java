package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.service;

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

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].playerId").value(playerId))
                .andExpect(jsonPath("$.entries[0].playerName").value("StreamingHero"))
                .andExpect(jsonPath("$.entries[0].score").value(scoreFor))
                .andExpect(jsonPath("$.entries[0].rank").value(1));
    }
}
