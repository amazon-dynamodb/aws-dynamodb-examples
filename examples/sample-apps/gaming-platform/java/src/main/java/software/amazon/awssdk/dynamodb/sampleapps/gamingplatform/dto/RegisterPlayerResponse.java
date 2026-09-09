package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for player registration.
 *
 * @param playerId internal player id
 * @param profile    profile snapshot
 * @param wallet     wallet snapshot
 * @param settings   settings snapshot
 * @param created    {@code true} if a new account was created, {@code false} for idempotent replay
 */
public record RegisterPlayerResponse(String playerId,
                                     ProfileSnapshot profile,
                                     WalletSnapshot wallet,
                                     SettingsSnapshot settings,
                                     boolean created) {
}
