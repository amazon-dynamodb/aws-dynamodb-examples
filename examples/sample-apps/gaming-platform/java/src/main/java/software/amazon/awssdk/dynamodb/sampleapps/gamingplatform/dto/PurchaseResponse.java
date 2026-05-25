package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for an in-game purchase.
 *
 * @param playerId        internal player id
 * @param profile         profile snapshot after purchase
 * @param wallet          wallet snapshot after purchase
 * @param settings        settings snapshot
 * @param status          {@code COMPLETED} or {@code IDEMPOTENT_REPLAY}
 * @param purchaseEventId id of the GameEvents audit row
 */
public record PurchaseResponse(String playerId,
                               ProfileSnapshot profile,
                               WalletSnapshot wallet,
                               SettingsSnapshot settings,
                               String status,
                               String purchaseEventId) {
}
