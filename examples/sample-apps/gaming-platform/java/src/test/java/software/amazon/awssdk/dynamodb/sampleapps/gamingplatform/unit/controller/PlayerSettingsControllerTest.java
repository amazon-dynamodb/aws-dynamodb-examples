package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerSettingsController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSettingsService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link PlayerSettingsController} using {@link WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(PlayerSettingsController.class)
class PlayerSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerSettingsService settingsService;

    @Test
    void getSettings_whenSettingsExist_shouldReturnSettingsSliceWithoutRootPlayerId() throws Exception {
        GetSettingsResponse response = new GetSettingsResponse(
                new SettingsSnapshot(true, "en", "PUBLIC", 1));
        when(settingsService.getSettings("p1")).thenReturn(response);

        mockMvc.perform(get("/api/v1/players/{playerId}/settings", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.settings.version").value(1));
    }

    @Test
    void getSettings_whenPlayerMissing_shouldReturn404() throws Exception {
        when(settingsService.getSettings("missing")).thenThrow(new PlayerNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/players/{playerId}/settings", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateSettings_whenPatchApplied_shouldReturnFullSnapshotWithSiblings() throws Exception {
        UpdatePlayerSettingsResponse response = new UpdatePlayerSettingsResponse(
                "p1",
                new ProfileSnapshot("N", "PC", 1, 0, "2026-01-01T00:00:00Z", 1),
                new WalletSnapshot(0, 1),
                new SettingsSnapshot(false, "de", "PRIVATE", 2));
        when(settingsService.updateSettings(eq("p1"), any())).thenReturn(response);

        mockMvc.perform(patch("/api/v1/players/{playerId}/settings", "p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notificationsEnabled": false,
                                  "preferredLanguage": "de",
                                  "profileVisibility": "PRIVATE",
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("p1"))
                .andExpect(jsonPath("$.profile.playerName").value("N"))
                .andExpect(jsonPath("$.wallet.version").value(1))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("de"))
                .andExpect(jsonPath("$.settings.version").value(2));
    }

    @Test
    void updateSettings_whenVersionStale_shouldReturn409() throws Exception {
        when(settingsService.updateSettings(eq("p1"), any()))
                .thenThrow(new StaleVersionException("p1", 999));

        mockMvc.perform(patch("/api/v1/players/{playerId}/settings", "p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "notificationsEnabled": true,
                                  "expectedVersion": 999
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void updateSettings_whenPlayerMissing_shouldReturn404() throws Exception {
        when(settingsService.updateSettings(eq("missing"), any()))
                .thenThrow(new PlayerNotFoundException("missing"));

        mockMvc.perform(patch("/api/v1/players/{playerId}/settings", "missing")
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
