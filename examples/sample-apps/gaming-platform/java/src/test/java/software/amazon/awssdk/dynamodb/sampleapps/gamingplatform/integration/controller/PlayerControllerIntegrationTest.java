package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PlayerController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc and DynamoDB Local. Covers player
 * registration, idempotent replay, profile reads, and snapshot response shapes on
 * {@code POST /api/v1/players} and {@code GET /api/v1/players/{playerId}/profile}.
 */
@Tag("integration")
class PlayerControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Autowired
    private PlayerMapper playerMapper;

    @Value("${dynamodb.player-state-table-name}")
    private String playerStateTableName;

    @Test
    void getProfile_whenSeededPlayer_shouldReturnProfile() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.profile.platform").value("PC"))
                .andExpect(jsonPath("$.profile.currentLevel").value(10));
    }

    @Test
    void getProfile_whenPlayerUnknown_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void getProfile_whenSeededPlayer_shouldReturnProfileSliceWithoutRootPlayerId() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/profile", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.profile.platform").value("PC"))
                .andExpect(jsonPath("$.wallet").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist());
    }

    @Test
    void registerPlayer_whenNewAccount_shouldCreateAndRetrievePlayer() throws Exception {
        String registerBody = """
                {
                  "platform": "PC",
                  "platformUserId": "integration-steam-001",
                  "playerName": "IntegrationWolf"
                }
                """;

        String playerId = mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.playerId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String extractedPlayerId = new ObjectMapper()
                .readTree(playerId)
                .get("playerId")
                .asText();

        mockMvc.perform(get("/api/v1/players/{playerId}/profile", extractedPlayerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.playerName").value("IntegrationWolf"))
                .andExpect(jsonPath("$.profile.platform").value("PC"))
                .andExpect(jsonPath("$.profile.currentLevel").value(1));

        // Verify that default settings were created atomically alongside the profile
        mockMvc.perform(get("/api/v1/players/{playerId}/settings", extractedPlayerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"));
    }

    @Test
    void registerPlayer_whenDuplicateRegister_shouldReturnExisting() throws Exception {
        String registerBody = """
                {
                  "platform": "IOS",
                  "platformUserId": "integration-apple-002",
                  "playerName": "DuplicateTest"
                }
                """;

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.profile.playerName").value("DuplicateTest"));
    }

    @Test
    void registerPlayer_whenNewAccount_shouldReturnFullSnapshotAtRoot() throws Exception {
        String platformUserId = "snapshot-contract-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "%s",
                                  "playerName": "SnapshotContractPlayer"
                                }
                                """.formatted(platformUserId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.playerId").isNotEmpty())
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.profile.playerName").value("SnapshotContractPlayer"))
                .andExpect(jsonPath("$.profile.platform").value("PC"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(PlayerWalletMapper.INITIAL_BALANCE))
                .andExpect(jsonPath("$.settings.notificationsEnabled").value(true))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.settings.profileVisibility").value("PUBLIC"));
    }

    @Test
    void registerPlayer_whenConflictingIdentity_shouldReturn409() throws Exception {
        String platformUserId = "integration-registration-clash-user";
        String playerId = playerMapper.generatePlayerId("PC", platformUserId);

        Map<String, AttributeValue> conflictingRow = Map.ofEntries(
                Map.entry("PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId)),
                Map.entry("SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE)),
                Map.entry("entityType", AttributeValue.fromS(PlayerProfile.ENTITY_TYPE)),
                Map.entry("playerId", AttributeValue.fromS(playerId)),
                Map.entry("platform", AttributeValue.fromS("ANDROID")),
                Map.entry("platformUserId", AttributeValue.fromS("foreign-user-handle")),
                Map.entry("playerName", AttributeValue.fromS("RogueOccupant")),
                Map.entry("currentLevel", AttributeValue.fromN("1")),
                Map.entry("totalExperience", AttributeValue.fromN("0")),
                Map.entry("currencyBalance", AttributeValue.fromN("0")),
                Map.entry("version", AttributeValue.fromN("1")),
                Map.entry("lastUpdatedAt", AttributeValue.fromS("2026-01-05T09:30:00Z")));

        dynamoDbAsyncClient.putItem(PutItemRequest.builder()
                .tableName(playerStateTableName)
                .item(conflictingRow)
                .build()).join();

        mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "PC",
                                  "platformUserId": "%s",
                                  "playerName": "LegitimateClaimant"
                                }
                                """.formatted(platformUserId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAYER_ALREADY_EXISTS"));
    }
}
