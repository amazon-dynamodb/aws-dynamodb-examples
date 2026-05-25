package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Wallet fields from the {@code SK = WALLET} item.
 *
 * @param currencyBalance soft currency balance
 * @param version         optimistic lock version for the wallet item
 */
public record WalletSnapshot(
        long currencyBalance,
        long version) {
}
