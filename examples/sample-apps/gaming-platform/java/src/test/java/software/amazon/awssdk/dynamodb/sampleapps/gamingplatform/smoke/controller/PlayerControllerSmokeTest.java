package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link PlayerController}.
 *
 * <p>Extends {@link AbstractSmokeTest} with MockMvc against DynamoDB Local. Reads the seeded
 * profile slice for {@link SeedPlayerData#SEED_PLAYER_1} and registers new PC and ANDROID accounts
 * with {@link PlayerWalletMapper#INITIAL_BALANCE} in the returned snapshot.
 */
@Tag("smoke")
class PlayerControllerSmokeTest extends AbstractSmokeTest {

    @Test
    void getProfile_whenSeededPlayer_shouldReturnProfileSliceWithoutRootPlayerId() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.profile.platform").value("PC"));
    }

    @Test
    void getProfile_whenSeededPlayer_shouldReturnProfileSliceOnly() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"));
    }

    @Test
    void registerPlayer_whenNewAccount_shouldReturnFullSnapshotAtRoot() throws Exception {
        String uniquePlatformUserId = "smoke-reg-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "%s",
                                  "playerName": "SmokeTestPlayer",
                                  "clientRequestId": "smoke-reg-req-%s"
                                }
                                """.formatted(uniquePlatformUserId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").isNotEmpty())
                .andExpect(jsonPath("$.profile.playerName").value("SmokeTestPlayer"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(PlayerWalletMapper.INITIAL_BALANCE))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"));
    }

    @Test
    void registerPlayer_whenNewAndroidAccount_shouldReturnFullSnapshotAtRoot() throws Exception {
        String platformUserId = "smoke-snapshot-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "ANDROID",
                                  "platformUserId": "%s",
                                  "playerName": "SmokeSnapshotPlayer"
                                }
                                """.formatted(platformUserId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").isNotEmpty())
                .andExpect(jsonPath("$.profile.playerName").value("SmokeSnapshotPlayer"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(PlayerWalletMapper.INITIAL_BALANCE))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"));
    }
}
