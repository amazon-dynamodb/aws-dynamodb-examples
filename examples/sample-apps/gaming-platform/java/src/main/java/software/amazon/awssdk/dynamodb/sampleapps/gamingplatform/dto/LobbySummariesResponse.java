package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.util.List;

/**
 * Response body for batch player summary lookups.
 *
 * @param summaries        profiles that were found
 * @param missingPlayerIds ids that had no matching profile
 */
public record LobbySummariesResponse(List<PlayerSummary> summaries, List<String> missingPlayerIds) {
}
