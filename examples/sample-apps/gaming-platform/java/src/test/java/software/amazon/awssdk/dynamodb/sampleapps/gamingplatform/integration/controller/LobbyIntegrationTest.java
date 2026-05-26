package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LobbyController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link LobbyController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc and DynamoDB Local. Covers batch lobby
 * summaries and platform browsing via the {@code GSI_PLATFORM_PLAYERS} index.
 */
@Tag("integration")
class LobbyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getLobbySummaries_whenPlayersSeeded_shouldReturnSummaries() throws Exception {
        mockMvc.perform(post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": ["%s", "%s"]
                                }
                                """.formatted(SeedPlayerData.SEED_PLAYER_1, SeedPlayerData.SEED_PLAYER_2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries", hasSize(2)))
                .andExpect(jsonPath("$.summaries[*].playerId",
                        containsInAnyOrder(SeedPlayerData.SEED_PLAYER_1, SeedPlayerData.SEED_PLAYER_2)))
                .andExpect(jsonPath("$.missingPlayerIds", hasSize(0)));
    }

    @Test
    void getLobbySummaries_whenSomePlayersMissing_shouldReportMissingIds() throws Exception {
        mockMvc.perform(post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": ["%s", "nonexistent-player"]
                                }
                                """.formatted(SeedPlayerData.SEED_PLAYER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries", hasSize(1)))
                .andExpect(jsonPath("$.summaries[0].playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.missingPlayerIds", hasSize(1)))
                .andExpect(jsonPath("$.missingPlayerIds[0]").value("nonexistent-player"));
    }

    @Test
    void getLobbySummaries_whenNoPlayersExist_shouldReturnAllMissing() throws Exception {
        mockMvc.perform(post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": ["ghost-1", "ghost-2"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries", hasSize(0)))
                .andExpect(jsonPath("$.missingPlayerIds", hasSize(2)));
    }

    @Test
    void getLobbySummaries_whenPlayerIdsEmpty_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void browseByPlatform_whenPlatformPc_shouldReturnPlayersOrderedByMostRecentFirst() throws Exception {
        // Seed data has 2 PC players: EchoNova (2026-04-01) and AlphaWolf (2026-01-15)
        mockMvc.perform(get("/api/v1/lobbies/platform/PC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("PC"))
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.players[0].playerName").value("EchoNova"))
                .andExpect(jsonPath("$.players[1].playerName").value("AlphaWolf"));
    }

    @Test
    void browseByPlatform_whenPlatformIos_shouldReturnPlayers() throws Exception {
        // Seed data has 2 IOS players: DeltaStrike (2026-03-25) and BraveFox (2026-02-20)
        mockMvc.perform(get("/api/v1/lobbies/platform/IOS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("IOS"))
                .andExpect(jsonPath("$.players", hasSize(2)))
                .andExpect(jsonPath("$.players[0].playerName").value("DeltaStrike"))
                .andExpect(jsonPath("$.players[1].playerName").value("BraveFox"));
    }

    @Test
    void browseByPlatform_whenPlatformAndroid_shouldReturnPlayers() throws Exception {
        // Seed data has 1 ANDROID player: CosmicRay
        mockMvc.perform(get("/api/v1/lobbies/platform/ANDROID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ANDROID"))
                .andExpect(jsonPath("$.players", hasSize(1)))
                .andExpect(jsonPath("$.players[0].playerName").value("CosmicRay"));
    }

    @Test
    void browseByPlatform_whenLimitProvided_shouldRespectLimit() throws Exception {
        mockMvc.perform(get("/api/v1/lobbies/platform/PC")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players", hasSize(1)))
                .andExpect(jsonPath("$.players[0].playerName").value("EchoNova"));
    }

    @Test
    void browseByPlatform_whenPlatformInvalid_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/lobbies/platform/XBOX"))
                .andExpect(status().isBadRequest());
    }
}
