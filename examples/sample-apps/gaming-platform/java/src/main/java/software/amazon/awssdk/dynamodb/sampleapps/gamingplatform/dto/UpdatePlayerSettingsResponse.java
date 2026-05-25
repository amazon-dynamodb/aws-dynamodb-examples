package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for a settings update.
 *
 * @param playerId internal player id
 * @param profile  profile snapshot
 * @param wallet   wallet snapshot
 * @param settings updated settings snapshot
 */
public record UpdatePlayerSettingsResponse(String playerId,
                                           ProfileSnapshot profile,
                                           WalletSnapshot wallet,
                                           SettingsSnapshot settings) {
}
