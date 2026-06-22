package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LeaderboardController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardEntryDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LeaderboardQueryService;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link LeaderboardController} using {@link WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(LeaderboardController.class)
class LeaderboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LeaderboardQueryService leaderboardQueryService;

    @Test
    void getLeaderboard_whenEntriesExist_shouldReturnLeaderboard() throws Exception {
        String scope = "SEASON#default#MODE#ranked";
        LeaderboardResponse response = new LeaderboardResponse(scope,
                List.of(
                        new LeaderboardEntryDto(1, "player-1", "AlphaWolf", 3000),
                        new LeaderboardEntryDto(2, "player-2", "BetaBear", 2500)));

        when(leaderboardQueryService.getTopN(scope, 10)).thenReturn(response);

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].rank").value(1))
                .andExpect(jsonPath("$.entries[0].playerId").value("player-1"))
                .andExpect(jsonPath("$.entries[0].score").value(3000))
                .andExpect(jsonPath("$.entries[1].rank").value(2));
    }

    @Test
    void getLeaderboard_whenLimitOmitted_shouldUseDefaultLimit() throws Exception {
        String scope = "SEASON#default#MODE#ranked";
        LeaderboardResponse response = new LeaderboardResponse(scope, List.of());

        when(leaderboardQueryService.getTopN(scope, 10)).thenReturn(response);

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void getLeaderboard_whenNoEntries_shouldReturnEmptyLeaderboard() throws Exception {
        String scope = "SEASON#unknown#MODE#ranked";
        LeaderboardResponse response = new LeaderboardResponse(scope, List.of());

        when(leaderboardQueryService.getTopN(scope, 5)).thenReturn(response);

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void getLeaderboard_whenScopeHasIllegalCharacter_shouldReturn400AndNotCallService() throws Exception {
        mockMvc.perform(get("/api/v1/leaderboards/{scope}", "bad!scope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        verifyNoInteractions(leaderboardQueryService);
    }
}
