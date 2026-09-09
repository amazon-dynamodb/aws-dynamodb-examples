package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Lightweight player summary for lobby hydration and platform GSI browse responses.
 *
 * @param playerId      internal id
 * @param playerName    display name
 * @param currentLevel  current level
 * @param lastUpdatedAt ISO-8601 UTC timestamp (included for GSI composite sort key browsing)
 */
public record PlayerSummary(
        String playerId,
        String playerName,
        int currentLevel,
        String lastUpdatedAt) {
}
