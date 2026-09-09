package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerWalletController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link PlayerWalletController}.
 *
 * <p>Verifies that a seeded player wallet can be read and credited with a unique
 * {@code clientRequestId}. Deeper earn and idempotency scenarios live in the integration wallet suite.
 *
 * <p>Extends {@link AbstractSmokeTest} and issues HTTP requests through MockMvc against DynamoDB Local
 * with the default high-level client.
 */
@Tag("smoke")
class PlayerWalletSmokeTest extends AbstractSmokeTest {

    @Test
    void getWallet_whenSeededPlayer_shouldReturnWalletSliceWithoutRootPlayerId() throws Exception {
        performAsync(mockMvc, get("/api/v1/players/{playerId}/wallet", SeedPlayerData.SEED_PLAYER_1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1200));
    }

    @Test
    void earnCurrency_whenCompleted_shouldReturnWalletFocusedResponseWithStatus() throws Exception {
        String clientRequestId = "smoke-earn-" + UUID.randomUUID();

        performAsync(mockMvc, post("/api/v1/players/{playerId}/wallet/earn", SeedPlayerData.SEED_PLAYER_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 50,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "%s"
                                }
                                """.formatted(clientRequestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value(SeedPlayerData.SEED_PLAYER_1))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1250))
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.settings").doesNotExist());
    }
}
