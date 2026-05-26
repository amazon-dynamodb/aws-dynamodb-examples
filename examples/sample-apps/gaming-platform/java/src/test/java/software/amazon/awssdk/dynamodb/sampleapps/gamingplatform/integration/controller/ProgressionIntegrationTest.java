package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.ProgressionController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ProgressionController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc, DynamoDB Local, table reset, and the
 * default high-level client. Uses {@link SeedPlayerData#SEED_PLAYER_1} to verify XP application,
 * optimistic locking conflicts, and unknown player handling on
 * {@code PATCH /api/v1/players/{playerId}/progression}.
 */
@Tag("integration")
class ProgressionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void updateProgression_whenValidPatch_shouldApplyXpAndReturnUpdatedProfile() throws Exception {
        mockMvc.perform(patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 25,
                                  "reason": "INTEGRATION_TEST",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.profile.platform").value("PC"))
                .andExpect(jsonPath("$.profile.totalExperience").value(3525))
                .andExpect(jsonPath("$.profile.currentLevel").value(4))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1200))
                .andExpect(jsonPath("$.profile.version").value(2))
                .andExpect(jsonPath("$.appliedEventId").value(nullValue()));
    }

    @Test
    void updateProgression_whenVersionStale_shouldReturn409() throws Exception {
        mockMvc.perform(patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 10,
                                  "reason": "STALE_EXPECTED_TEST",
                                  "expectedVersion": 999999
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }

    @Test
    void updateProgression_whenPlayerUnknown_shouldReturn404() throws Exception {
        mockMvc.perform(patch("/api/v1/players/{playerId}/progression", "nonexistent-progression-player")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 10,
                                  "reason": "NOT_FOUND_CASE",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void updateProgression_whenXpThresholdCrossed_shouldLevelUp() throws Exception {
        mockMvc.perform(patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 15000,
                                  "reason": "LEVEL_UP_INTEGRATION",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.profile.totalExperience").value(18500))
                .andExpect(jsonPath("$.profile.currentLevel").value(19));
    }

    @Test
    void updateProgression_whenUnknownFieldsPresent_shouldReturnWalletAndIgnoreUnknownFields() throws Exception {
        mockMvc.perform(patch("/api/v1/players/{playerId}/progression", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "xpDelta": 0,
                                  "currencyDelta": -2000,
                                  "reason": "OVERDRAFT_ATTEMPT",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1200))
                .andExpect(jsonPath("$.profile.version").value(2));
    }
}
