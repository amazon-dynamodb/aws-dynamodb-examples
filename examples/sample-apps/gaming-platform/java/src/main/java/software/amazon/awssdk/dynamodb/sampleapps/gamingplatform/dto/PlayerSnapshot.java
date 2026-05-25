package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Full player state composed from the PROFILE, WALLET, and SETTINGS items.
 *
 * @param playerId internal player id
 * @param profile  profile snapshot
 * @param wallet   wallet snapshot
 * @param settings settings snapshot
 */
public record PlayerSnapshot(
        String playerId,
        ProfileSnapshot profile,
        WalletSnapshot wallet,
        SettingsSnapshot settings) {
}
