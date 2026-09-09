package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import java.util.concurrent.CompletableFuture;
import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LobbyController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlatformPlayersResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSummary;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LobbyService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link LobbyController} using {@link WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(LobbyController.class)
class LobbyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LobbyService lobbyService;

    @Test
    void getLobbySummaries_whenPlayersExist_shouldReturnSummaries() throws Exception {
        PlayerSummary summary = new PlayerSummary("player-1", "AlphaWolf", 10, "2026-01-15T10:00:00Z");
        LobbySummariesResponse response = new LobbySummariesResponse(
                List.of(summary), List.of("player-unknown"));

        when(lobbyService.getLobbySummaries(any(LobbySummariesRequest.class))).thenReturn(CompletableFuture.completedFuture(response));

        performAsync(mockMvc, post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "playerIds": ["player-1", "player-unknown"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summaries").isArray())
                .andExpect(jsonPath("$.summaries[0].playerId").value("player-1"))
                .andExpect(jsonPath("$.summaries[0].playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.summaries[0].currentLevel").value(10))
                .andExpect(jsonPath("$.missingPlayerIds[0]").value("player-unknown"));
    }

    @Test
    void getLobbySummaries_whenPlayerIdsEmpty_shouldReturn400() throws Exception {
        performAsync(mockMvc, post("/api/v1/lobbies/summaries")
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
    void getLobbySummaries_whenPlayerIdsMissing_shouldReturn400() throws Exception {
        performAsync(mockMvc, post("/api/v1/lobbies/summaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void browseByPlatform_whenValidPlatform_shouldReturnPlayers() throws Exception {
        PlayerSummary summary = new PlayerSummary("player-1", "AlphaWolf", 10, "2026-01-15T10:00:00Z");
        PlatformPlayersResponse response = new PlatformPlayersResponse("PC", List.of(summary));

        when(lobbyService.getPlayersByPlatform("PC", 20)).thenReturn(CompletableFuture.completedFuture(response));

        performAsync(mockMvc, get("/api/v1/lobbies/platform/PC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("PC"))
                .andExpect(jsonPath("$.players").isArray())
                .andExpect(jsonPath("$.players[0].playerId").value("player-1"));
    }

    @Test
    void browseByPlatform_whenCustomLimitProvided_shouldReturnPlayers() throws Exception {
        PlatformPlayersResponse response = new PlatformPlayersResponse("IOS", List.of());

        when(lobbyService.getPlayersByPlatform("IOS", 5)).thenReturn(CompletableFuture.completedFuture(response));

        performAsync(mockMvc, get("/api/v1/lobbies/platform/IOS")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("IOS"))
                .andExpect(jsonPath("$.players").isEmpty());
    }

    @Test
    void browseByPlatform_whenPlatformInvalid_shouldReturn400() throws Exception {
        when(lobbyService.getPlayersByPlatform("XBOX", 20))
                .thenThrow(new IllegalArgumentException("Invalid platform: XBOX"));

        performAsync(mockMvc, get("/api/v1/lobbies/platform/XBOX"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void browseByPlatform_whenPlatformHasIllegalCharacter_shouldReturn400AndNotCallService() throws Exception {
        performAsync(mockMvc, get("/api/v1/lobbies/platform/{platform}", "PC!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(lobbyService);
    }
}
