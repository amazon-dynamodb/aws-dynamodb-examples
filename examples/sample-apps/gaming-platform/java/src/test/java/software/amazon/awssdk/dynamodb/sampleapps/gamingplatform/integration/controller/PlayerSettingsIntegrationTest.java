package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerSettingsController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PlayerSettingsController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc, DynamoDB Local, and the default
 * high-level client. Reads and patches settings for {@link SeedPlayerData#SEED_PLAYER_1} including
 * partial updates, stale version conflicts, and unknown player paths.
 */
@Tag("integration")
class PlayerSettingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getSettings_whenSeededPlayer_shouldReturnSettingsSliceWithoutRootPlayerId() throws Exception {
        performAsync(mockMvc, get("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.wallet").doesNotExist());
    }

    @Test
    void getSettings_whenSeedPlayer_shouldReturnDefaultSettings() throws Exception {
        performAsync(mockMvc, get("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.settings.version").value(1));
    }

    @Test
    void getSettings_whenPlayerUnknown_shouldReturn404() throws Exception {
        performAsync(mockMvc, get("/api/v1/players/{playerId}/settings", "nonexistent-player"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateSettings_whenValidPatch_shouldReturnNewValues() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notificationsEnabled": false,
                                  "preferredLanguage": "de",
                                  "profileVisibility": "FRIENDS_ONLY",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(false))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("de"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("FRIENDS_ONLY"))
                .andExpect(jsonPath("$.settings.version").value(2));
    }

    @Test
    void updateSettings_whenPartialPatch_shouldApplyUpdate() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "preferredLanguage": "ja",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.preferredLanguage").value("ja"))
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"));
    }

    @Test
    void updateSettings_whenProfileVisibilityValid_shouldApplyUpdate() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileVisibility": "FRIENDS_ONLY",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.profileVisibility").value("FRIENDS_ONLY"));
    }

    @Test
    void updateSettings_whenProfileVisibilityInvalid_shouldReturn400() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileVisibility": "SUPER_SECRET",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void updateSettings_whenVersionStale_shouldReturn409() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notificationsEnabled": false,
                                  "expectedVersion": 999
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }

    @Test
    void updateSettings_whenValidPatch_shouldReturnSettingsFocusedResponse() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "preferredLanguage": "fr",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_3))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("fr"))
                .andExpect(jsonPath("$.settings.version").value(2))
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.wallet").doesNotExist());
    }

    @Test
    void updateSettings_whenPlayerUnknown_shouldReturn404() throws Exception {
        performAsync(mockMvc, patch("/api/v1/players/{playerId}/settings", "nonexistent-settings-player")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notificationsEnabled": true,
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isNotFound());
    }
}
