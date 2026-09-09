package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.util.List;

/**
 * Response body for browsing players by platform using {@code GSI_PLATFORM_PLAYERS}.
 *
 * @param platform echoed platform filter
 * @param players  summaries ordered by most recently active first
 */
public record PlatformPlayersResponse(String platform, List<PlayerSummary> players) {
}
