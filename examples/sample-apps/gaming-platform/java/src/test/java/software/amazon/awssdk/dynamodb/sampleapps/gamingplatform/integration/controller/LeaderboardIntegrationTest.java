package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LeaderboardController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LeaderboardRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.DynamoDbStreamsLeaderboardListener;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link LeaderboardController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc and DynamoDB Local. Seeds leaderboard
 * rows through {@link LeaderboardRepository} helpers and asserts ordering, empty scopes, and
 * limit handling on {@code GET /api/v1/leaderboards/{scope}}.
 */
@Tag("integration")
class LeaderboardIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LeaderboardRepository leaderboardRepository;

    @Test
    void getLeaderboard_whenFreshScope_shouldReturnEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/leaderboards/{scope}", "SEASON#unknown#MODE#ranked"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SEASON#unknown#MODE#ranked"))
                .andExpect(jsonPath("$.entries").isEmpty());
    }

    @Test
    void getLeaderboard_whenEntriesExist_shouldReturnOrderedByScoreDescending() throws Exception {
        String scope = DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE + "-populated-tests";

        putEntry(scope, SeedPlayerData.SEED_PLAYER_1, "Alice", 100);
        putEntry(scope, SeedPlayerData.SEED_PLAYER_2, "Bob", 500);
        putEntry(scope, SeedPlayerData.SEED_PLAYER_3, "Cara", 300);

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value(scope))
                .andExpect(jsonPath("$.entries[0].playerId").value(SeedPlayerData.SEED_PLAYER_2))
                .andExpect(jsonPath("$.entries[0].score").value(500))
                .andExpect(jsonPath("$.entries[1].score").value(300))
                .andExpect(jsonPath("$.entries[2].score").value(100));
    }

    @Test
    void getLeaderboard_whenLimitProvided_shouldRespectLimit() throws Exception {
        String scope = DynamoDbStreamsLeaderboardListener.DEFAULT_SCOPE + "-limit-tests";

        putEntry(scope, SeedPlayerData.SEED_PLAYER_1, "Alice", 400);
        putEntry(scope, SeedPlayerData.SEED_PLAYER_2, "Bob", 300);
        putEntry(scope, SeedPlayerData.SEED_PLAYER_3, "Cara", 500);
        putEntry(scope, SeedPlayerData.SEED_PLAYER_4, "Dan", 200);

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope).queryParam("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].score").value(500))
                .andExpect(jsonPath("$.entries[1].score").value(400));

        mockMvc.perform(get("/api/v1/leaderboards/{scope}", scope).queryParam("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[2].score").value(300));
    }

    /**
     * Inserts a leaderboard entry directly via the repository for test setup.
     *
     * @param scope      leaderboard scope key
     * @param playerId   player identifier
     * @param playerName display name
     * @param score      player score used to build the sort key
     */
    private void putEntry(String scope, String playerId, String playerName, long score) {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPartitionKey(LeaderboardEntry.buildPartitionKey(scope));
        entry.setSortKey(LeaderboardEntry.buildSortKey(score, playerId));
        entry.setEntityType(LeaderboardEntry.ENTITY_TYPE);
        entry.setPlayerId(playerId);
        entry.setPlayerName(playerName);
        entry.setScore(score);
        entry.setLastUpdatedAt("2026-01-01T00:00:00Z");
        leaderboardRepository.putLeaderboardEntry(entry).join();
    }
}
