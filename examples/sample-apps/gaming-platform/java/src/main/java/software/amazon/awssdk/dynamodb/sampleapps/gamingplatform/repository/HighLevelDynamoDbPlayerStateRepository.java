package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.RetryHelper;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactUpdateItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * High-level {@link DynamoDbEnhancedAsyncClient} implementation of {@link PlayerStateRepository}.
 *
 * <p>Uses the enhanced client's type-safe {@link DynamoDbAsyncTable} API for CRUD, batch get,
 * queries, and {@link TransactWriteItemsEnhancedRequest}. Optimistic locking on {@link PlayerProfile}
 * relies on {@link VersionedRecordExtension} and
 * {@link DynamoDbVersionAttribute}
 * on the profile bean.
 *
 * <p>{@link #batchGetPlayers} uses {@link DynamoDbEnhancedAsyncClient#batchGetItem} with
 * {@link RetryHelper} to drain
 * unprocessed keys.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
public class HighLevelDynamoDbPlayerStateRepository implements PlayerStateRepository {

    private static final Logger logger = LoggerFactory.getLogger(HighLevelDynamoDbPlayerStateRepository.class);

    /** DynamoDB caps {@code BatchGetItem} at this many keys per table per request. */
    private static final int BATCH_GET_KEYS_LIMIT = 100;

    /** Bean schema for {@link PlayerProfile}. */
    private static final TableSchema<PlayerProfile> PLAYER_SCHEMA = TableSchema.fromBean(PlayerProfile.class);

    /** Bean schema for {@link PlayerSettings}. */
    private static final TableSchema<PlayerSettings> SETTINGS_SCHEMA = TableSchema.fromBean(PlayerSettings.class);

    /** Bean schema for {@link PlayerWallet}. */
    private static final TableSchema<PlayerWallet> WALLET_SCHEMA = TableSchema.fromBean(PlayerWallet.class);

    /** Bean schema for {@link GameEvent}, used to marshal earn transaction events. */
    private static final TableSchema<GameEvent> EVENT_SCHEMA = TableSchema.fromBean(GameEvent.class);

    /** Update expression crediting currency and bumping the wallet version under optimistic lock. */
    private static final String EARN_CURRENCY_EXPRESSION =
            "SET currencyBalance = currencyBalance + :amount, version = version + :one";

    /** Condition that guards the wallet credit: wallet must exist and version must match. */
    private static final String EARN_WALLET_CONDITION =
            "attribute_exists(PK) AND version = :expectedVersion";

    /** Enhanced client for transact writes and batch get. */
    private final DynamoDbEnhancedAsyncClient enhancedClient;

    /**
     * Low-level client used exclusively for {@link #earnCurrencyTransaction} because the enhanced
     * client's {@link TransactUpdateItemEnhancedRequest} applies {@link VersionedRecordExtension}
     * automatically, but credits must use a raw {@code ADD} expression without a version condition.
     */
    private final DynamoDbAsyncClient dynamoDbClient;

    /** Main player table handle. */
    private final DynamoDbAsyncTable<PlayerProfile> profileTable;

    /** Settings table handle (same physical table, different schema). */
    private final DynamoDbAsyncTable<PlayerSettings> settingsTable;

    /** Wallet table handle (same physical table, different schema). */
    private final DynamoDbAsyncTable<PlayerWallet> walletTable;

    /** GameEvent table handle for purchase transactions. */
    private final DynamoDbAsyncTable<GameEvent> gameEventsTable;

    /** Physical PlayerState table name, used in low-level earn transaction. */
    private final String tableName;

    /** Physical GameEvent table name, used in low-level earn transaction. */
    private final String gameEventsTableName;

    /**
     * Creates the repository.
     *
     * @param enhancedClient      the high-level enhanced async client
     * @param dynamoDbClient      the low-level async client (for earn ADD transactions)
     * @param tableName           PlayerState table name
     * @param gameEventsTableName GameEvent table name
     */
    public HighLevelDynamoDbPlayerStateRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            DynamoDbAsyncClient dynamoDbClient,
            @Value("${dynamodb.player-state-table-name}") String tableName,
            @Value("${dynamodb.game-events-table-name}") String gameEventsTableName) {
        this.enhancedClient = enhancedClient;
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.gameEventsTableName = gameEventsTableName;
        this.profileTable = enhancedClient.table(tableName, PLAYER_SCHEMA);
        this.settingsTable = enhancedClient.table(tableName, SETTINGS_SCHEMA);
        this.walletTable = enhancedClient.table(tableName, WALLET_SCHEMA);
        this.gameEventsTable = enhancedClient.table(gameEventsTableName,
                TableSchema.fromBean(GameEvent.class));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses {@link PutItemEnhancedRequest} with {@code attribute_not_exists(PK)}.
     */
    @Override
    public CompletableFuture<Void> createPlayer(PlayerProfile profile) {
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(PK)")
                .build();

        PutItemEnhancedRequest<PlayerProfile> request = PutItemEnhancedRequest.builder(PlayerProfile.class)
                .item(profile)
                .conditionExpression(condition)
                .build();

        return profileTable.putItem(request)
                .thenRun(() -> logger.debug("Player profile created [playerId={}]",
                        profile.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @param playerId internal player id
     * @return the profile, or {@code null} when the item is missing
     */
    @Override
    public CompletableFuture<PlayerProfile> getPlayer(String playerId) {
        Key key = Key.builder()
                .partitionValue(PlayerProfile.PK_PREFIX + playerId)
                .sortValue(PlayerProfile.SK_PROFILE)
                .build();

        return profileTable.getItem(key);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Applies target progression scalars with {@link UpdateItemEnhancedRequest} and
     *           {@code ignoreNulls(true)}. {@link VersionedRecordExtension}
     *           supplies the version precondition and increments {@code version} on success.
     */
    @Override
    public CompletableFuture<PlayerProfile> updateProgression(String playerId, long newTotalExperience,
                                                               int newLevel, long expectedVersion) {
        PlayerProfile profileProgressionUpdate = new PlayerProfile();
        profileProgressionUpdate.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        profileProgressionUpdate.setSortKey(PlayerProfile.SK_PROFILE);
        profileProgressionUpdate.setTotalExperience(newTotalExperience);
        profileProgressionUpdate.setCurrentLevel(newLevel);
        profileProgressionUpdate.setLastUpdatedAt(Instant.now().toString());
        profileProgressionUpdate.setVersion(expectedVersion);

        UpdateItemEnhancedRequest<PlayerProfile> request = UpdateItemEnhancedRequest.builder(PlayerProfile.class)
                .item(profileProgressionUpdate)
                .ignoreNulls(true)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return profileTable.updateItem(request)
                .thenApply(updated -> {
                    logger.debug("Player progression updated [playerId={}]", playerId);
                    return updated;
                });
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses {@link TransactWriteItemsEnhancedRequest}: a versioned partial update on the
     *           wallet with a currency guard and a conditional put on the purchase event.
     */
    @Override
    public CompletableFuture<Void> purchaseTransaction(PlayerWallet currentWallet, long softCurrencyCost,
                                                        String itemId, GameEvent purchaseEvent) {
        PlayerWallet walletDebitUpdate = new PlayerWallet();
        walletDebitUpdate.setPartitionKey(currentWallet.getPartitionKey());
        walletDebitUpdate.setSortKey(PlayerWallet.SK_WALLET);
        walletDebitUpdate.setCurrencyBalance(currentWallet.getCurrencyBalance() - softCurrencyCost);
        walletDebitUpdate.setVersion(currentWallet.getVersion());

        // Condition ensures the wallet has enough balance before the debit proceeds
        Expression fundsCondition = Expression.builder()
                .expression("currencyBalance >= :cost")
                .expressionValues(Map.of(
                        ":cost", AttributeValue.fromN(String.valueOf(softCurrencyCost))))
                .build();

        TransactUpdateItemEnhancedRequest<PlayerWallet> updateWallet =
                TransactUpdateItemEnhancedRequest.builder(PlayerWallet.class)
                        .item(walletDebitUpdate)
                        .ignoreNulls(true)
                        .conditionExpression(fundsCondition)
                        .build();

        // Event put uses attribute_not_exists so duplicate purchase ids fail the transaction.
        Expression eventAbsent = Expression.builder()
                .expression("attribute_not_exists(PK)")
                .build();

        TransactPutItemEnhancedRequest<GameEvent> putEvent =
                TransactPutItemEnhancedRequest.builder(GameEvent.class)
                        .item(purchaseEvent)
                        .conditionExpression(eventAbsent)
                        .build();

        // Items are added in WalletTransactItemOrder: WALLET (index 0) then EVENT (index 1), so the
        // cancellation-reason positions line up with PurchaseService.
        TransactWriteItemsEnhancedRequest txRequest = TransactWriteItemsEnhancedRequest.builder()
                .addUpdateItem(walletTable, updateWallet)
                .addPutItem(gameEventsTable, putEvent)
                .build();

        return enhancedClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Purchase transaction completed [playerId={}]",
                        currentWallet.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote The enhanced client's {@link TransactUpdateItemEnhancedRequest} applies
     *           {@link VersionedRecordExtension} automatically with its own attribute handling, so
     *           this method delegates to the injected {@link DynamoDbAsyncClient} and builds the
     *           {@code TransactWriteItems} request manually. The wallet credit increments
     *           {@code currencyBalance} and bumps {@code version} under an explicit optimistic-lock
     *           guard ({@code version = :expectedVersion}), mirroring the purchase debit. Index 0 is
     *           the wallet credit. Index 1 is the event PUT (guarded by
     *           {@code attribute_not_exists(PK)} for idempotency).
     */
    @Override
    public CompletableFuture<Void> earnCurrencyTransaction(PlayerWallet currentWallet, long amount,
                                                            GameEvent rewardEvent) {
        // Uses the low-level client to control the wallet version handling explicitly
        Map<String, AttributeValue> walletKey = Map.of(
                "PK", AttributeValue.fromS(currentWallet.getPartitionKey()),
                "SK", AttributeValue.fromS(PlayerWallet.SK_WALLET));

        Map<String, AttributeValue> earnValues = Map.of(
                ":amount", AttributeValue.fromN(String.valueOf(amount)),
                ":one", AttributeValue.fromN("1"),
                ":expectedVersion", AttributeValue.fromN(String.valueOf(currentWallet.getVersion())));

        // Index 0: credit with version guard and version bump for optimistic locking
        Update addCurrency = Update.builder()
                .tableName(tableName)
                .key(walletKey)
                .updateExpression(EARN_CURRENCY_EXPRESSION)
                .conditionExpression(EARN_WALLET_CONDITION)
                .expressionAttributeValues(earnValues)
                .build();

        // Index 1: idempotency guard rejects duplicate earn events via attribute_not_exists(PK)
        Map<String, AttributeValue> eventItem = EVENT_SCHEMA.itemToMap(rewardEvent, true);

        Put putEvent = Put.builder()
                .tableName(gameEventsTableName)
                .item(eventItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        TransactWriteItem[] transactItems = new TransactWriteItem[WalletTransactItemOrder.values().length];
        transactItems[WalletTransactItemOrder.WALLET.index()] =
                TransactWriteItem.builder().update(addCurrency).build();
        transactItems[WalletTransactItemOrder.EVENT.index()] =
                TransactWriteItem.builder().put(putEvent).build();

        TransactWriteItemsRequest txRequest = TransactWriteItemsRequest.builder()
                .transactItems(transactItems)
                .build();

        return dynamoDbClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Earn currency transaction completed [playerId={}]",
                        currentWallet.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Chunks requests to at most 100 keys per {@code BatchGetItem}.
     *           Each chunk is loaded with {@link DynamoDbEnhancedAsyncClient#batchGetItem} and
     *           {@link RetryHelper#executeEnhancedBatchGetUntilComplete} so unprocessed keys are
     *           retried with backoff.
     */
    @Override
    public CompletableFuture<List<PlayerProfile>> batchGetPlayers(List<String> playerIds) {
        if (playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<Key> keys = playerIds.stream()
                .map(id -> Key.builder()
                        .partitionValue(PlayerProfile.PK_PREFIX + id)
                        .sortValue(PlayerProfile.SK_PROFILE)
                        .build())
                .toList();

        List<PlayerProfile> combined = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        // DynamoDB accepts at most 100 keys per BatchGetItem round per table.
        for (int i = 0; i < keys.size(); i += BATCH_GET_KEYS_LIMIT) {
            int end = Math.min(i + BATCH_GET_KEYS_LIMIT, keys.size());
            List<Key> chunk = keys.subList(i, end);
            chain = chain.thenCompose(ignored ->
                    RetryHelper.executeEnhancedBatchGetUntilComplete(
                                    enhancedClient,
                                    profileTable,
                                    new ArrayList<>(chunk),
                                    RetryHelper.BATCH_GET_MAX_ROUNDS,
                                    RetryHelper.BATCH_GET_BASE_DELAY_MS)
                            .thenAccept(combined::addAll));
        }
        return chain.thenApply(ignored -> combined);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses the enhanced client's {@link DynamoDbAsyncIndex#query} with
     *           {@code scanIndexForward(false)} so results come back ordered by
     *           {@code lastUpdatedAt} descending (most recent first).
     */
    @Override
    public CompletableFuture<List<PlayerProfile>> queryPlayersByPlatform(String platform, int limit) {
        DynamoDbAsyncIndex<PlayerProfile> index = profileTable.index(PlayerProfile.GSI_PLATFORM_PLAYERS);

        QueryEnhancedRequest queryRequest = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(platform).build()))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        CompletableFuture<List<PlayerProfile>> result = new CompletableFuture<>();
        List<PlayerProfile> collected = new ArrayList<>();

        index.query(queryRequest).subscribe(new Subscriber<>() {
            /** Upstream subscription handle used to request pages and cancel the stream. */
            private Subscription subscription;

            /** @param s upstream subscription */
            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            /** @param page first page of GSI results */
            @Override
            public void onNext(Page<PlayerProfile> page) {
                collected.addAll(page.items());
                subscription.cancel();
                result.complete(collected);
            }

            /** @param t failure from the publisher */
            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            /** Completes with whatever was collected if the publisher ends without error. */
            @Override
            public void onComplete() {
                if (!result.isDone()) {
                    result.complete(collected);
                }
            }
        });

        return result;
    }


    /**
     * {@inheritDoc}
     *
     * @implNote Uses {@link TransactWriteItemsEnhancedRequest} with three conditional puts
     *           on the same partition for PROFILE, SETTINGS, and WALLET items atomically.
     */
    @Override
    public CompletableFuture<Void> createPlayerWithSettingsAndWallet(PlayerProfile profile,
                                                                      PlayerSettings settings,
                                                                      PlayerWallet wallet) {
        // All three items share the same PK. Each put is guarded against overwrite
        Expression absent = Expression.builder()
                .expression("attribute_not_exists(PK)")
                .build();

        TransactPutItemEnhancedRequest<PlayerProfile> putProfile =
                TransactPutItemEnhancedRequest.builder(PlayerProfile.class)
                        .item(profile)
                        .conditionExpression(absent)
                        .build();

        TransactPutItemEnhancedRequest<PlayerSettings> putSettings =
                TransactPutItemEnhancedRequest.builder(PlayerSettings.class)
                        .item(settings)
                        .conditionExpression(absent)
                        .build();

        TransactPutItemEnhancedRequest<PlayerWallet> putWallet =
                TransactPutItemEnhancedRequest.builder(PlayerWallet.class)
                        .item(wallet)
                        .conditionExpression(absent)
                        .build();

        TransactWriteItemsEnhancedRequest txRequest = TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(profileTable, putProfile)
                .addPutItem(settingsTable, putSettings)
                .addPutItem(walletTable, putWallet)
                .build();

        return enhancedClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Player registration transaction completed [playerId={}]",
                        profile.getPlayerId()));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<PlayerWallet> getWallet(String playerId) {
        Key key = Key.builder()
                .partitionValue(PlayerProfile.PK_PREFIX + playerId)
                .sortValue(PlayerWallet.SK_WALLET)
                .build();

        return walletTable.getItem(key);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<PlayerSettings> getSettings(String playerId) {
        Key key = Key.builder()
                .partitionValue(PlayerProfile.PK_PREFIX + playerId)
                .sortValue(PlayerSettings.SK_SETTINGS)
                .build();

        return settingsTable.getItem(key);
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses {@link UpdateItemEnhancedRequest} with {@code ignoreNulls(true)} and
     *           {@link VersionedRecordExtension} for optimistic locking.
     */
    @Override
    public CompletableFuture<PlayerSettings> updateSettings(PlayerSettings settings) {
        UpdateItemEnhancedRequest<PlayerSettings> request = UpdateItemEnhancedRequest.builder(PlayerSettings.class)
                .item(settings)
                .ignoreNulls(true)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return settingsTable.updateItem(request)
                .thenApply(updated -> {
                    logger.debug("Player settings updated [playerId={}]",
                            settings.getPlayerId());
                    return updated;
                });
    }
}
