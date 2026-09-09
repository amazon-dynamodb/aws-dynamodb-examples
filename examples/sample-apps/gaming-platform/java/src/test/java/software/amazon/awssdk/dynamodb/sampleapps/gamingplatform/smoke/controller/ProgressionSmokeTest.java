package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.ProgressionController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link ProgressionController}.
 *
 * <p>Extends {@link AbstractSmokeTest} with MockMvc against seeded player
 * {@link SeedPlayerData#SEED_PLAYER_1}. Applies a small XP patch with matching
 * {@code expectedVersion} and asserts a full root snapshot. Also verifies stale version returns
 * HTTP 409 with {@code STALE_VERSION}.
 */
@Tag("smoke")
class ProgressionSmokeTest extends AbstractSmokeTest {

    @Test
    void updateProgression_whenValidPatch_shouldReturnFullSnapshotAtRoot() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 10,
                                  "reason": "SMOKE_TEST",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.profile.totalExperience").exists())
                .andExpect(jsonPath("$.wallet.currencyBalance").exists())
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"));
    }

    @Test
    void updateProgression_whenVersionStale_shouldReturn409() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 0,
                                  "reason": "SMOKE_STALE_VERSION",
                                  "expectedVersion": 999
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }
}
