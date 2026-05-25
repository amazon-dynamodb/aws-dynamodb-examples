package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Data access contract for the PlayerState DynamoDB table.
 *
 * <p>Two implementations are provided: one using the low-level {@code DynamoDbAsyncClient}
 * and one using the high-level {@code DynamoDbEnhancedAsyncClient}. The active implementation
 * is selected at startup by {@code dynamodb.client-type}.
 */
public interface PlayerStateRepository {

    /**
     * Creates a new player profile with a conditional check that prevents overwriting
     * an existing item ({@code attribute_not_exists(PK)}).
     *
     * @param profile profile to persist
     * @throws ConditionalCheckFailedException
     *         if a profile with the same PK already exists
     */
    CompletableFuture<Void> createPlayer(PlayerProfile profile);

    /**
     * Retrieves a player profile by id.
     *
     * @param playerId internal player id
     * @return the profile, or {@code null} if not found
     */
    CompletableFuture<PlayerProfile> getPlayer(String playerId);

    /**
     * Atomically updates progression fields (XP, level) using optimistic locking
     * on the version attribute.
     *
     * @param playerId            the player's id
     * @param newTotalExperience  absolute {@code totalExperience} to persist (caller applies deltas)
     * @param newLevel            {@code currentLevel} computed for the new experience total
     * @param expectedVersion     version from the caller's last read. Must match DynamoDB
     * @return the updated profile after the write
     * @throws ConditionalCheckFailedException
     *         if the version does not match or the player does not exist
     */
    CompletableFuture<PlayerProfile> updateProgression(String playerId, long newTotalExperience,
                                                        int newLevel, long expectedVersion);

    /**
     * Executes a cross-table ACID transaction that deducts currency from the player wallet
     * on PlayerState and writes a purchase event to GameEvents.
     *
     * @param currentWallet  the player's wallet with the current balance and version
     * @param softCurrencyCost positive currency amount to debit
     * @param itemId         identifier of the purchased item (embedded in the event)
     * @param purchaseEvent  the {@code ITEM_PURCHASED} audit event to persist alongside the debit
     * @throws TransactionCanceledException
     *         if any condition fails (insufficient funds, stale version, duplicate event)
     */
    CompletableFuture<Void> purchaseTransaction(PlayerWallet currentWallet, long softCurrencyCost,
                                                 String itemId, GameEvent purchaseEvent);

    /**
     * Atomically credits soft currency to a player wallet and writes a {@code CURRENCY_GRANT}
     * GameEvent to the GameEvents table.
     *
     * <p>The wallet update uses a DynamoDB {@code ADD} expression so concurrent credits do not
     * conflict and no version check is required. A caller-supplied idempotency key is embedded
     * in the {@code rewardEvent} sort key. The {@code attribute_not_exists(PK)} condition on the
     * event put detects duplicate calls and triggers a {@link TransactionCanceledException} that
     * the service interprets as {@code IDEMPOTENT_REPLAY}.
     *
     * @param playerId    the player to credit
     * @param amount      positive soft currency amount to add
     * @param rewardEvent the {@code CURRENCY_GRANT} audit event to persist alongside the credit
     * @throws TransactionCanceledException if the wallet does not exist (index 0) or the event
     *                                      is a duplicate (index 1)
     */
    CompletableFuture<Void> earnCurrencyTransaction(String playerId, long amount, GameEvent rewardEvent);

    /**
     * Batch-reads player profiles for the given ids, retrying until all
     * {@code UnprocessedKeys} are drained.
     *
     * @param playerIds internal player ids to load
     * @return profiles that were found. Order is not guaranteed to match the input list.
     */
    CompletableFuture<List<PlayerProfile>> batchGetPlayers(List<String> playerIds);

    /**
     * Queries the {@code GSI_PLATFORM_PLAYERS} index for recently active players
     * on the given platform, ordered by most recently active first.
     *
     * @param platform platform partition key for the GSI
     * @param limit    maximum profiles to return
     * @return profiles ordered by {@code lastUpdatedAt} descending
     */
    CompletableFuture<List<PlayerProfile>> queryPlayersByPlatform(String platform, int limit);


    /**
     * Atomically creates a {@link PlayerProfile}, default {@link PlayerSettings},
     * and default {@link PlayerWallet} using {@code TransactWriteItems}.
     * All puts use {@code attribute_not_exists(PK)} conditions so the transaction
     * fails if any item already exists.
     *
     * @param profile  the new player profile
     * @param settings the default settings row
     * @param wallet   the default wallet row
     * @throws TransactionCanceledException if any item already exists
     */
    CompletableFuture<Void> createPlayerWithSettingsAndWallet(PlayerProfile profile,
                                                              PlayerSettings settings,
                                                              PlayerWallet wallet);

    /**
     * Retrieves a player's wallet by id.
     *
     * @param playerId the internal player id
     * @return the wallet, or {@code null} if not found
     */
    CompletableFuture<PlayerWallet> getWallet(String playerId);

    /**
     * Retrieves a player's settings by id.
     *
     * @param playerId the internal player id
     * @return the settings, or {@code null} if not found
     */
    CompletableFuture<PlayerSettings> getSettings(String playerId);

    /**
     * Updates a player's settings using optimistic locking on the version attribute.
     *
     * @param settings the settings bean with updated fields and the expected version
     * @return the updated settings after the write
     * @throws ConditionalCheckFailedException if the version does not match
     */
    CompletableFuture<PlayerSettings> updateSettings(PlayerSettings settings);
}
