package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerSettingsController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link PlayerSettingsController}.
 *
 * <p>Extends {@link AbstractSmokeTest} and reads settings for {@link SeedPlayerData#SEED_PLAYER_1}
 * through MockMvc. Asserts the settings slice omits root {@code playerId} and returns default
 * notification and language values.
 */
@Tag("smoke")
class PlayerSettingsSmokeTest extends AbstractSmokeTest {

    @Test
    void getSettings_whenSeededPlayer_shouldReturnSettingsSliceWithoutRootPlayerId() throws Exception {
        performAsync(mockMvc, get("/api/v1/players/{playerId}/settings", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"));
    }
}
