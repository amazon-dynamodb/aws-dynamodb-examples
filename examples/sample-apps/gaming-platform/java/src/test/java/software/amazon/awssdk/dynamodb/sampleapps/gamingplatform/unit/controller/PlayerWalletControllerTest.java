package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.PlayerWalletController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetWalletResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.CurrencyRewardService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerWalletService;

/**
 * Slice tests for {@link PlayerWalletController} using {@code @WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(PlayerWalletController.class)
class PlayerWalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlayerWalletService walletService;

    @MockitoBean
    private CurrencyRewardService currencyRewardService;

    @Test
    void getWallet_whenWalletExists_shouldReturnWalletSliceWithoutRootPlayerId() throws Exception {
        when(walletService.getWallet("player-001"))
                .thenReturn(new GetWalletResponse(new WalletSnapshot(1000L, 1L)));

        mockMvc.perform(get("/api/v1/players/player-001/wallet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").doesNotExist())
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1000))
                .andExpect(jsonPath("$.wallet.version").value(1));
    }

    @Test
    void getWallet_whenPlayerMissing_shouldReturn404() throws Exception {
        when(walletService.getWallet("missing"))
                .thenThrow(new PlayerNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/players/missing/wallet"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void earnCurrency_whenCompleted_shouldReturnFullSnapshotWithOperationFields() throws Exception {
        WalletEarnResponse response = new WalletEarnResponse(
                "player-001",
                new ProfileSnapshot("N", "PC", 1, 0, "2026-01-01T00:00:00Z", 1),
                new WalletSnapshot(1300L, 2L),
                new SettingsSnapshot(true, "en", "PUBLIC", 1),
                "COMPLETED",
                "evt-earn-1");

        when(currencyRewardService.grantCurrency(eq("player-001"), any(WalletEarnRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/players/player-001/wallet/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "req-earn-001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.playerId").value("player-001"))
                .andExpect(jsonPath("$.profile.playerName").value("N"))
                .andExpect(jsonPath("$.settings.preferredLanguage").value("en"))
                .andExpect(jsonPath("$.wallet.currencyBalance").value(1300))
                .andExpect(jsonPath("$.earnEventId").value("evt-earn-1"));
    }

    @Test
    void earnCurrency_whenDuplicateRequest_shouldReturnIdempotentReplayStatus() throws Exception {
        WalletEarnResponse response = new WalletEarnResponse(
                "player-001",
                new ProfileSnapshot("N", "PC", 1, 0, "2026-01-01T00:00:00Z", 1),
                new WalletSnapshot(1300L, 2L),
                new SettingsSnapshot(true, "en", "PUBLIC", 1),
                "IDEMPOTENT_REPLAY",
                "evt-earn-dup");

        when(currencyRewardService.grantCurrency(eq("player-001"), any(WalletEarnRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/players/player-001/wallet/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300,
                                  "reason": "MATCH_WIN",
                                  "clientRequestId": "req-earn-dup"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_REPLAY"));
    }

    @Test
    void earnCurrency_whenPlayerMissing_shouldReturn404() throws Exception {
        when(currencyRewardService.grantCurrency(eq("missing"), any(WalletEarnRequest.class)))
                .thenThrow(new PlayerNotFoundException("missing"));

        mockMvc.perform(post("/api/v1/players/missing/wallet/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100,
                                  "reason": "DAILY_LOGIN",
                                  "clientRequestId": "req-nf"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void earnCurrency_whenAmountNotPositive_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/players/player-001/wallet/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 0,
                                  "reason": "DAILY_LOGIN",
                                  "clientRequestId": "req-bad"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void earnCurrency_whenClientRequestIdMissing_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/players/player-001/wallet/earn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100,
                                  "reason": "MATCH_WIN"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }
}
