package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetProfileResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerAlreadyExistsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerProfileService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerRegistrationService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link PlayerController} using {@link WebMvcTest}.
 *
 * <p>Mocks registration and profile services to verify HTTP status codes, validation, and JSON
 * response shapes for player registration and profile reads.
 */
@Tag("unit")
@WebMvcTest(PlayerController.class)
class PlayerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerRegistrationService registrationService;

    @MockitoBean
    private PlayerProfileService profileService;

    @Test
    void registerPlayer_whenNewAccount_shouldReturn201WithFullSnapshot() throws Exception {
        RegisterPlayerResponse response = registerResponse("player-1", "TestPlayer", "PC", 1, 0, 0, 1, true);

        when(registrationService.registerPlayer(any(RegisterPlayerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "steam-123",
                                  "playerName": "TestPlayer"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").value("player-1"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.profile.playerName").value("TestPlayer"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(0))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"));
    }

    @Test
    void registerPlayer_whenIdempotentReplay_shouldReturn200WithExistingSnapshot() throws Exception {
        RegisterPlayerResponse response = registerResponse("player-1", "TestPlayer", "PC", 5, 1000, 500, 3, false);

        when(registrationService.registerPlayer(any(RegisterPlayerRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "steam-123",
                                  "playerName": "TestPlayer"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value("player-1"))
                .andExpect(jsonPath("$.created").value(false));
    }

    @Test
    void registerPlayer_whenConflictingIdentity_shouldReturn409() throws Exception {
        when(registrationService.registerPlayer(any(RegisterPlayerRequest.class)))
                .thenThrow(new PlayerAlreadyExistsException("player-1"));

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "steam-123",
                                  "playerName": "TestPlayer"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAYER_ALREADY_EXISTS"));
    }

    @Test
    void registerPlayer_whenPlatformMissing_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platformUserId": "steam-123",
                                  "playerName": "TestPlayer"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getProfile_whenPlayerExists_shouldReturnProfileSliceWithoutRootPlayerId() throws Exception {
        GetProfileResponse profileResponse = new GetProfileResponse(
                new ProfileSnapshot("AlphaWolf", "PC", 10, 3500, "2026-01-15T10:00:00Z", 1));

        when(profileService.getProfile("player-1")).thenReturn(profileResponse);

        mockMvc.perform(get("/api/v1/players/player-1/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.profile.currentLevel").value(10));
    }

    @Test
    void getProfile_whenPlayerMissing_shouldReturn404() throws Exception {
        when(profileService.getProfile("unknown"))
                .thenThrow(new PlayerNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/players/unknown/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void getProfile_whenPlayerIdTooLong_shouldReturn400AndNotCallService() throws Exception {
        String tooLong = "a".repeat(65);

        mockMvc.perform(get("/api/v1/players/{playerId}/profile", tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(profileService);
    }

    @Test
    void getProfile_whenPlayerIdHasIllegalCharacter_shouldReturn400AndNotCallService() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", "bad!id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(profileService);
    }

    /**
     * Builds a {@link RegisterPlayerResponse} fixture for controller stubbing.
     *
     * @param playerId       generated player id
     * @param name           display name
     * @param platform       gaming platform code
     * @param level          current level
     * @param xp             total experience
     * @param balance        starter wallet balance
     * @param profileVersion profile optimistic-lock version
     * @param created        whether the player row was newly created
     * @return registration response payload
     */
    private static RegisterPlayerResponse registerResponse(String playerId,
                                                           String name,
                                                           String platform,
                                                           int level,
                                                           long xp,
                                                           long balance,
                                                           long profileVersion,
                                                           boolean created) {
        return new RegisterPlayerResponse(
                playerId,
                new ProfileSnapshot(name, platform, level, xp, "2026-01-01T00:00:00Z", profileVersion),
                new WalletSnapshot(balance, profileVersion),
                new SettingsSnapshot(true, "en", "PUBLIC", 1),
                created);
    }
}
