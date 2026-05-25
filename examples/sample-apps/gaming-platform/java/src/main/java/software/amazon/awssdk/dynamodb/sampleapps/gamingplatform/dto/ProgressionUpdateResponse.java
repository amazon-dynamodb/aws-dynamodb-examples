package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for progression updates.
 *
 * @param playerId       internal player id
 * @param profile        updated profile snapshot
 * @param wallet         wallet snapshot
 * @param settings       settings snapshot
 * @param appliedEventId id of the appended GameEvents audit row when present, otherwise {@code null}
 */
public record ProgressionUpdateResponse(String playerId,
                                        ProfileSnapshot profile,
                                        WalletSnapshot wallet,
                                        SettingsSnapshot settings,
                                        String appliedEventId) {
}
