package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Names the order of items in the wallet {@code TransactWriteItems} requests shared by the purchase
 * and earn flows.
 *
 * <p>{@link PlayerStateRepository#purchaseTransaction} and
 * {@link PlayerStateRepository#earnCurrencyTransaction} both build a two-item transaction in the same
 * fixed order: the wallet write first, then the conditional event put
 * ({@code attribute_not_exists(PK)}). DynamoDB reports per-item cancellation reasons in the same
 * order, so the {@linkplain #index() index} of each constant doubles as the position of that item's
 * reason in {@link TransactionCanceledException#cancellationReasons()}.
 *
 * <p>The producers ({@code LowLevelDynamoDbPlayerStateRepository},
 * {@code HighLevelDynamoDbPlayerStateRepository}) and the consumers
 * ({@code PurchaseService} and {@code CurrencyRewardService}) all reference this enum, so the
 * transact item order is defined once. Reordering the items means reordering these constants, which
 * keeps both ends aligned instead of letting a producer change silently break conflict detection.
 *
 * <p>This enum applies only to the two-item wallet transactions. Player registration uses a
 * three-item transaction ({@code PROFILE}, {@code SETTINGS}, {@code WALLET}) and decides idempotency
 * by reading the profile back rather than by index, so it does not use this enum.
 *
 * <p>Constants are declared in the same order the items are written, so {@link Enum#ordinal()} equals
 * {@link #index()}.
 */
public enum WalletTransactItemOrder {

    /** Wallet write. A debit ({@code purchase}) or an atomic {@code ADD} ({@code earn}). */
    WALLET,

    /** Conditional put of the domain event, guarded by {@code attribute_not_exists(PK)}. */
    EVENT;

    /**
     * @return zero-based position of this item in the wallet transact write and in the matching
     *     cancellation-reason list, equal to {@link Enum#ordinal()}
     */
    public int index() {
        return ordinal();
    }
}

