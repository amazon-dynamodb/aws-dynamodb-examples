package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import java.util.concurrent.CompletableFuture;
import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.ProgressionController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.ProgressionService;

/**
 * Slice tests for {@link ProgressionController} using {@code @WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(ProgressionController.class)
class ProgressionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProgressionService progressionService;

    @Test
    void updateProgression_whenValidPatch_shouldReturn200() throws Exception {
        ProgressionUpdateResponse response = new ProgressionUpdateResponse(
                "player-001",
                new ProfileSnapshot("Ace", "STEAM", 5, 4500L, "2025-01-01T00:00:00Z", 2L),
                new WalletSnapshot(1200L, 2L),
                new SettingsSnapshot(true, "en", "PUBLIC", 1),
                null);

        when(progressionService.updateProgression(eq("player-001"), any(ProgressionUpdateRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        performAsync(mockMvc, patch("/api/v1/players/player-001/progression")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 500,
                                  "reason": "QUEST_REWARD",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("player-001"))
                .andExpect(jsonPath("$.profile.currentLevel").value(5));
    }

    @Test
    void updateProgression_whenVersionStale_shouldReturn409() throws Exception {
        when(progressionService.updateProgression(eq("player-001"), any(ProgressionUpdateRequest.class)))
                .thenThrow(new StaleVersionException("player-001", 1L));

        performAsync(mockMvc, patch("/api/v1/players/player-001/progression")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 500,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }

    @Test
    void updateProgression_whenExpectedVersionMissing_shouldReturn400() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/player-001/progression")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 500
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateProgression_whenPlayerNotFound_shouldReturn404() throws Exception {
        when(progressionService.updateProgression(eq("ghost-id"), any(ProgressionUpdateRequest.class)))
                .thenThrow(new PlayerNotFoundException("ghost-id"));

        performAsync(mockMvc, patch("/api/v1/players/ghost-id/progression")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 50,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }
}
