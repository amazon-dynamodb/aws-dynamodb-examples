package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PurchaseController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link PurchaseController}.
 *
 * <p>Extends {@link AbstractSmokeTest} with the full Spring context, DynamoDB Local from
 * Testcontainers, and the default high-level client. Uses seed player
 * {@link SeedPlayerData#SEED_PLAYER_1} who starts with 1200 soft currency.
 *
 * <p>Posts a 100-cost purchase through MockMvc and asserts {@code COMPLETED} plus the debited
 * balance in the root-level wallet snapshot.
 */
@Tag("smoke")
class PurchaseSmokeTest extends AbstractSmokeTest {

    @Test
    void completePurchase_whenFundsAvailable_returnsFullSnapshotWithStatus() throws Exception {
        String clientRequestId = "smoke-purchase-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/players/{playerId}/purchases", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "smoke-catalog-item",
                                  "softCurrencyCost": 100,
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.profile.playerName").value("AlphaWolf"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1100));
    }
}
