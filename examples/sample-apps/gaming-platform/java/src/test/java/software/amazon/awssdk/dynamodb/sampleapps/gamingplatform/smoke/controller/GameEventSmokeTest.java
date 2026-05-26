package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.GameEventController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link GameEventController}.
 *
 * <p>Extends {@link AbstractSmokeTest} with MockMvc against DynamoDB Local and seeded players.
 * Records a {@code PVP_MATCH} event for {@link SeedPlayerData#SEED_PLAYER_1} and asserts HTTP 201
 * with a non-empty {@code eventId}.
 */
@Tag("smoke")
class GameEventSmokeTest extends AbstractSmokeTest {

    @Test
    void recordEvent_whenValidRequest_shouldSucceed() throws Exception {
        mockMvc.perform(post("/api/v1/players/{playerId}/events", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PVP_MATCH",
                                  "eventAttributes": {
                                    "matchId": "smoke-match-01",
                                    "opponentPlayerId": "opponent-smoke",
                                    "result": "WIN",
                                    "playerScore": 3,
                                    "opponentScore": 1
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").isNotEmpty());
    }
}
