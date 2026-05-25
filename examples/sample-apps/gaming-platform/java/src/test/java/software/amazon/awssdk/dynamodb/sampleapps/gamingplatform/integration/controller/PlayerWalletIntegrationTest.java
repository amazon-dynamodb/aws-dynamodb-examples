package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerWalletController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PlayerWalletController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc and DynamoDB Local. Covers balance
 * retrieval, currency credit, idempotent replay on the same {@code clientRequestId},
 * wallet-not-found paths, validation errors, and snapshot response shapes.
 */
@Tag("integration")
class PlayerWalletIntegrationTest extends AbstractIntegrationTest {


    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;


    @Autowired
    private PlayerMapper playerMapper;


    @Value("${dynamodb.player-state-table-name}")
    private String playerStateTableName;

    @Test
    void getWallet_whenSeededPlayer_returnsWalletSliceWithoutRootPlayerId() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/wallet", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1200))
                .andExpect(jsonPath("$.wallet.version").value(1))
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist());
    }

    @Test
    void shouldReturnSeededWallet() throws Exception {
        mockMvc.perform(get("/api/v1/players/{playerId}/wallet", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1200))
                .andExpect(jsonPath("$.wallet.version").value(1));
    }

    @Test
    void shouldCreditWalletOnEarn() throws Exception {
        String clientRequestId = "integration-earn-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 150,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_2))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1100))
                .andExpect(jsonPath("$.earnEventId").isNotEmpty());
    }

    @Test
    void shouldReturnIdempotentReplayOnDuplicateEarn() throws Exception {
        String clientRequestId = "integration-earn-idem-" + UUID.randomUUID();
        String body = """
                {
                  "amount": 75,
                  "reason": "DAILY_LOGIN",
                  "clientRequestId": "%s"
                }
                """.formatted(clientRequestId);

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1025));
    }

    @Test
    void shouldReturn404ForUnknownPlayerOnEarn() throws Exception {
        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", "unknown-wallet-player")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 50,
                                  "reason": "ADMIN_GRANT",
                                  "clientRequestId": "req-unknown"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
    }

    @Test
    void shouldReturn404WhenWalletRowMissing() throws Exception {
        String platformUserId = "integration-wallet-missing-user";
        String playerId = playerMapper.generatePlayerId("PC", platformUserId);

        Map<String, AttributeValue> profileOnly = Map.ofEntries(
                Map.entry("PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId)),
                Map.entry("SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE)),
                Map.entry("entityType", AttributeValue.fromS(PlayerProfile.ENTITY_TYPE)),
                Map.entry("playerId", AttributeValue.fromS(playerId)),
                Map.entry("platform", AttributeValue.fromS("PC")),
                Map.entry("platformUserId", AttributeValue.fromS(platformUserId)),
                Map.entry("playerName", AttributeValue.fromS("ProfileOnly")),
                Map.entry("currentLevel", AttributeValue.fromN("1")),
                Map.entry("totalExperience", AttributeValue.fromN("0")),
                Map.entry("version", AttributeValue.fromN("1")),
                Map.entry("lastUpdatedAt", AttributeValue.fromS("2026-01-01T00:00:00Z")));

        dynamoDbAsyncClient.putItem(PutItemRequest.builder()
                .tableName(playerStateTableName)
                .item(profileOnly)
                .build()).join();

        mockMvc.perform(get("/api/v1/players/{playerId}/wallet", playerId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"));
    }

    @Test
    void earnCurrency_whenCompleted_returnsFullSnapshotWithOperationFields() throws Exception {
        String clientRequestId = "snapshot-contract-earn-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_4)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 25,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_4))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.profile.playerName").value("DeltaStrike"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(775))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.earnEventId").isNotEmpty());
    }

    @Test
    void shouldReturn400WhenAmountNotPositive() throws Exception {
        mockMvc.perform(post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 0,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "req-zero"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
