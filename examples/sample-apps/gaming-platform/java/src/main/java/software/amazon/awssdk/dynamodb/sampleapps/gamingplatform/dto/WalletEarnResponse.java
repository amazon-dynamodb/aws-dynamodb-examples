package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body for a wallet earn (currency credit) operation.
 *
 * <p>Deliberately wallet-focused: an earn only mutates the wallet, so the response returns the
 * wallet slice rather than the full player aggregate (profile and settings are unchanged and can
 * be read from their own endpoints).
 *
 * @param playerId    internal player id
 * @param wallet      wallet snapshot after the credit (balance and optimistic-lock version)
 * @param status      {@code COMPLETED} when the credit was applied, {@code IDEMPOTENT_REPLAY} when
 *                    the same {@code clientRequestId} was already processed
 * @param earnEventId id of the {@code CURRENCY_GRANT} GameEvent written as the audit record
 */
public record WalletEarnResponse(String playerId,
                                 WalletSnapshot wallet,
                                 String status,
                                 String earnEventId) {
}
