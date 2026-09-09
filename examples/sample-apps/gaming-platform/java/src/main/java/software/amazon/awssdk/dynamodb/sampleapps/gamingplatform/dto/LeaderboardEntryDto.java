package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Single entry in a leaderboard response.
 *
 * @param rank       1-based rank position
 * @param playerId   player identifier
 * @param playerName display name (denormalized)
 * @param score      aggregate score
 */
public record LeaderboardEntryDto(int rank, String playerId, String playerName, long score) {
}
