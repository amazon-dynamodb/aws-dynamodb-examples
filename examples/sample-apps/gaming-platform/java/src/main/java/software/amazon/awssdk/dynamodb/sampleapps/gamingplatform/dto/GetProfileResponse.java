package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for {@code GET /api/v1/players/{playerId}/profile}.
 *
 * @param profile profile snapshot slice
 */
public record GetProfileResponse(ProfileSnapshot profile) {
}
