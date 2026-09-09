package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for player registration.
 *
 * <p>Registration is idempotent by platform identity: retries with the same
 * {@code platform} and {@code platformUserId} return the existing player profile
 * instead of creating a duplicate account.
 *
 * @param platform        gaming platform (e.g. IOS, ANDROID, PC)
 * @param platformUserId  stable identity from the platform provider
 * @param playerName      display name chosen by the player
 */
public record RegisterPlayerRequest(
        @NotBlank String platform,
        @NotBlank String platformUserId,
        @NotBlank String playerName) {
}
