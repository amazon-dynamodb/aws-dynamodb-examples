package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PurchaseController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link PurchaseController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc, DynamoDB Local, table reset, and the
 * default high-level client. Uses {@link SeedPlayerData#SEED_PLAYER_1} to exercise completed
 * purchases, idempotent replay, insufficient funds, and unknown player paths.
 */
@Tag("integration")
class PurchaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldCompletePurchaseForSeededPlayer() throws Exception {
        String clientRequestId = "integration-purchase-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "integration-catalog-item",
                                  "softCurrencyCost": 100,
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1100))
                .andExpect(jsonPath("$.wallet.version").value(2))
                .andExpect(jsonPath("$.purchaseEventId", not(emptyOrNullString())));
    }

    @Test
    void shouldReturnIdempotentReplayWhenSamePurchaseRetried() throws Exception {
        String clientRequestId = "integration-purchase-idem-" + UUID.randomUUID();
        String body = """
                {
                  "itemId": "integration-idem-item",
                  "softCurrencyCost": 50,
                  "clientRequestId": "%s"
                }
                """.formatted(clientRequestId);

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1150))
                .andExpect(jsonPath("$.wallet.version").value(2));

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1150))
                .andExpect(jsonPath("$.wallet.version").value(2));
    }

    /** Verifies spend attempts beyond available soft currency yield HTTP 409. */
    @Test
    void shouldReturn409WhenInsufficientFunds() throws Exception {
        String clientRequestId = "integration-insufficient-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "mega-pack",
                                  "softCurrencyCost": 999999,
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    /** Verifies purchase attempts against unknown wallets map to HTTP 404. */
    @Test
    void shouldReturn404ForUnknownPlayer() throws Exception {
        String clientRequestId = "integration-missing-buyer-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", "missing-buyer-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "bonus-crate",
                                  "softCurrencyCost": 10,
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }
}
