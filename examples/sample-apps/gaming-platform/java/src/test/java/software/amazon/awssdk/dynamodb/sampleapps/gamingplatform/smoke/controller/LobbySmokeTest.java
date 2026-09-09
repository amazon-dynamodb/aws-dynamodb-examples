package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LobbyController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link LobbyController}.
 *
 * <p>Extends {@link AbstractSmokeTest} with seeded players {@link SeedPlayerData#SEED_PLAYER_1}
 * and {@link SeedPlayerData#SEED_PLAYER_2}. Exercises batch lobby summaries and platform browse
 * for {@code PC} through MockMvc.
 */
@Tag("smoke")
class LobbySmokeTest extends AbstractSmokeTest {

    @Test
    void getLobbySummaries_whenPlayersSeeded_shouldReturnSummaries() throws Exception {
        performAsync(mockMvc, post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": ["%s", "%s"]
                                }
                                """.formatted(SeedPlayerData.SEED_PLAYER_1, SeedPlayerData.SEED_PLAYER_2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries", hasSize(2)));
    }

    @Test
    void browseByPlatform_whenPlatformProvided_shouldReturnPlayers() throws Exception {
        performAsync(mockMvc, get("/api/v1/lobbies/platform/PC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("PC"))
                .andExpect(jsonPath("$.players").isArray());
    }
}
