package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.util.List;

/**
 * Response body for leaderboard queries.
 *
 * @param scope   echoed scope key (e.g. SEASON#s1#MODE#ranked)
 * @param entries ranked entries sorted by score descending
 */
public record LeaderboardResponse(String scope, List<LeaderboardEntryDto> entries) {
}
