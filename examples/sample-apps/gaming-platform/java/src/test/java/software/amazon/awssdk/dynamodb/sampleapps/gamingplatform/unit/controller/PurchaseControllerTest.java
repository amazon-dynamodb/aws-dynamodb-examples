package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import java.util.concurrent.CompletableFuture;
import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PurchaseController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InsufficientFundsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PurchaseService;

/**
 * Slice tests for {@link PurchaseController} using {@code @WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(PurchaseController.class)
class PurchaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseService purchaseService;

    @Test
    void completePurchase_whenValidRequest_shouldReturn200() throws Exception {
        PurchaseResponse response = new PurchaseResponse(
                "player-002",
                new ProfileSnapshot("BuyerPro", "XBOX", 3, 2000L, "2025-01-01T00:00:00Z", 2L),
                new WalletSnapshot(4500L, 2L),
                new SettingsSnapshot(true, "en", "PUBLIC", 1),
                "COMPLETED",
                "evt-001");

        when(purchaseService.executePurchase(eq("player-002"), any(PurchaseRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        performAsync(mockMvc, post("/api/v1/players/player-002/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "sword-01",
                                  "softCurrencyCost": 500,
                                  "clientRequestId": "req-abc-123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value("player-002"))
                .andExpect(jsonPath("$.purchaseEventId").value("evt-001"));
    }

    @Test
    void completePurchase_whenInsufficientFunds_shouldReturn409() throws Exception {
        when(purchaseService.executePurchase(eq("player-002"), any(PurchaseRequest.class)))
                .thenThrow(new InsufficientFundsException("player-002", "epic-armor", 500L));

        performAsync(mockMvc, post("/api/v1/players/player-002/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "epic-armor",
                                  "softCurrencyCost": 500,
                                  "clientRequestId": "req-xyz-789"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void completePurchase_whenRequiredFieldsMissing_shouldReturn400() throws Exception {
        performAsync(mockMvc, post("/api/v1/players/player-002/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "softCurrencyCost": 500
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void completePurchase_whenPlayerNotFound_shouldReturn404() throws Exception {
        when(purchaseService.executePurchase(eq("missing-player"), any(PurchaseRequest.class)))
                .thenThrow(new PlayerNotFoundException("missing-player"));

        performAsync(mockMvc, post("/api/v1/players/missing-player/purchases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemId": "sword-01",
                                  "softCurrencyCost": 100,
                                  "clientRequestId": "req-not-found"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }
}
