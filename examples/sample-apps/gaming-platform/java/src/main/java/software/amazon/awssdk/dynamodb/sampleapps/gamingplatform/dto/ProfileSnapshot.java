package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Profile fields from the {@code SK = PROFILE} item, without wallet or settings data.
 *
 * @param playerName      display name
 * @param platform        gaming platform
 * @param currentLevel    current level
 * @param totalExperience cumulative XP
 * @param lastUpdatedAt   ISO-8601 UTC timestamp
 * @param version         optimistic lock version for the profile item
 */
public record ProfileSnapshot(
        String playerName,
        String platform,
        int currentLevel,
        long totalExperience,
        String lastUpdatedAt,
        long version) {
}
