package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for a wallet earn (currency credit) operation.
 *
 * @param playerId  internal player id
 * @param profile   profile snapshot
 * @param wallet    wallet snapshot after the credit
 * @param settings  settings snapshot
 * @param status    {@code COMPLETED} when the credit was applied,
 *                  {@code IDEMPOTENT_REPLAY} when the same {@code clientRequestId}
 *                  was already processed
 * @param earnEventId id of the {@code CURRENCY_GRANT} GameEvent written as the audit record
 */
public record WalletEarnResponse(String playerId,
                                 ProfileSnapshot profile,
                                 WalletSnapshot wallet,
                                 SettingsSnapshot settings,
                                 String status,
                                 String earnEventId) {
}
