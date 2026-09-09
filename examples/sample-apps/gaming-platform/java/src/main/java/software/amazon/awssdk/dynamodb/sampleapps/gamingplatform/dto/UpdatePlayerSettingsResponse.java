package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for a settings update.
 *
 * <p>Deliberately settings-focused: a settings update only mutates the settings item, so the
 * response returns the settings slice rather than the full player aggregate (profile and wallet are
 * unchanged and can be read from their own endpoints).
 *
 * @param playerId internal player id
 * @param settings updated settings snapshot (preferences and optimistic-lock version)
 */
public record UpdatePlayerSettingsResponse(String playerId,
                                           SettingsSnapshot settings) {
}
