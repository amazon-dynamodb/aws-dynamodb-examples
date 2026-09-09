package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request body for batch-loading player summaries.
 *
 * @param playerIds list of player ids to look up (max 100)
 */
public record LobbySummariesRequest(
        @NotEmpty @Size(max = 100) List<String> playerIds) {
}
