package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.RetryHelper;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Low-level {@link DynamoDbAsyncClient} implementation of {@link PlayerStateRepository}.
 *
 * <p>Uses {@link TableSchema#fromBean(Class)} for marshalling and unmarshalling between
 * {@link PlayerProfile} beans and DynamoDB item maps, while issuing requests through the
 * low-level async client for full control over expressions and conditions.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "low-level")
public class LowLevelDynamoDbPlayerStateRepository implements PlayerStateRepository {

    private static final Logger logger = LoggerFactory.getLogger(LowLevelDynamoDbPlayerStateRepository.class);

    /** Bean schema for {@link PlayerProfile}. */
    private static final TableSchema<PlayerProfile> PLAYER_SCHEMA = TableSchema.fromBean(PlayerProfile.class);

    /** Bean schema for {@link GameEvent} in transact writes. */
    private static final TableSchema<GameEvent> EVENT_SCHEMA = TableSchema.fromBean(GameEvent.class);

    /** Bean schema for {@link PlayerSettings}. */
    private static final TableSchema<PlayerSettings> SETTINGS_SCHEMA = TableSchema.fromBean(PlayerSettings.class);

    /** Bean schema for {@link PlayerWallet}. */
    private static final TableSchema<PlayerWallet> WALLET_SCHEMA = TableSchema.fromBean(PlayerWallet.class);

    /** Update expression for settings fields and version bump. */
    private static final String UPDATE_SETTINGS_EXPRESSION =
            "SET notificationsEnabled = :notif, "
                    + "preferredLanguage = :lang, "
                    + "profileVisibility = :vis, "
                    + "version = version + :one";

    /** Condition for settings updates (exists and version match). */
    private static final String SETTINGS_CONDITION =
            "attribute_exists(PK) AND version = :expectedVersion";

    /** Update expression for progression scalar fields, GSI composite sort key, and version bump. */
    private static final String UPDATE_PROGRESSION_EXPRESSION =
            "SET totalExperience = :txp, "
                    + "currentLevel = :newLevel, "
                    + "version = version + :one, "
                    + "lastUpdatedAt = :now, "
                    + "playerId = :playerId";

    /** Condition for progression updates (exists and version match). */
    private static final String PROGRESSION_CONDITION =
            "attribute_exists(PK) AND version = :expectedVersion";

    /** Condition for purchase wallet leg (funds and version). */
    private static final String PURCHASE_CONDITION =
            "attribute_exists(PK) AND currencyBalance >= :cost AND version = :expectedVersion";

    /** Update expression debiting currency on purchase. */
    private static final String PURCHASE_UPDATE_EXPRESSION =
            "SET currencyBalance = currencyBalance - :cost, "
                    + "version = version + :one";

    /** Update expression crediting currency on earn. Server-side ADD requires no version check. */
    private static final String EARN_CURRENCY_EXPRESSION = "ADD currencyBalance :amount";

    /** Condition for earn: wallet item must exist (guards against phantom credits). */
    private static final String EARN_WALLET_CONDITION = "attribute_exists(PK)";

    /** Low-level async DynamoDB dynamoDbAsyncClient. */
    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    /** Physical PlayerState table name. */
    private final String tableName;

    /** Physical GameEvents table name. */
    private final String gameEventsTableName;

    /**
     * Creates the repository.
     *
     * @param dynamoDbAsyncClient the low-level DynamoDB async client
     * @param tableName          PlayerState table name
     * @param gameEventsTableName GameEvents table name
     */
    public LowLevelDynamoDbPlayerStateRepository(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${dynamodb.player-state-table-name}") String tableName,
            @Value("${dynamodb.game-events-table-name}") String gameEventsTableName) {
        this.dynamoDbAsyncClient = dynamoDbAsyncClient;
        this.tableName = tableName;
        this.gameEventsTableName = gameEventsTableName;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Sets {@code version = 1} on the profile before marshalling. Unlike the high-level
     *           client there is no {@link VersionedRecordExtension},
     *           so the initial version must be written explicitly (aligned with {@link SeedPlayerData}).
     */
    @Override
    public CompletableFuture<Void> createPlayer(PlayerProfile profile) {
        profile.setVersion(1);
        Map<String, AttributeValue> item = PLAYER_SCHEMA.itemToMap(profile, true);

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        return dynamoDbAsyncClient.putItem(request)
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
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId),
                "SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE));

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build();

        return dynamoDbAsyncClient.getItem(request)
                .thenApply(response -> {
                    if (!response.hasItem() || response.item().isEmpty()) {
                        return null;
                    }
                    return PLAYER_SCHEMA.mapToItem(response.item());
                });
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses a low-level {@code UpdateItem} with explicit version check and increment.
     */
    @Override
    public CompletableFuture<PlayerProfile> updateProgression(String playerId, long newTotalExperience,
                                                               int newLevel, long expectedVersion) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId),
                "SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE));

        // :playerId is included so the GSI composite sort key stays projected
        Map<String, AttributeValue> expressionValues = Map.of(
                ":txp", AttributeValue.fromN(String.valueOf(newTotalExperience)),
                ":newLevel", AttributeValue.fromN(String.valueOf(newLevel)),
                ":one", AttributeValue.fromN("1"),
                ":now", AttributeValue.fromS(Instant.now().toString()),
                ":expectedVersion", AttributeValue.fromN(String.valueOf(expectedVersion)),
                ":playerId", AttributeValue.fromS(playerId));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(UPDATE_PROGRESSION_EXPRESSION)
                .conditionExpression(PROGRESSION_CONDITION)
                .expressionAttributeValues(expressionValues)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return dynamoDbAsyncClient.updateItem(request)
                .thenApply(response -> PLAYER_SCHEMA.mapToItem(response.attributes()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Builds a two-item {@code TransactWriteItems} request ordered by
     *           {@link WalletTransactItemOrder}: the wallet debit then the conditional purchase event
     *           put.
     */
    @Override
    public CompletableFuture<Void> purchaseTransaction(PlayerWallet currentWallet, long softCurrencyCost,
                                                        String itemId, GameEvent purchaseEvent) {
        Map<String, AttributeValue> walletKey = Map.of(
                "PK", AttributeValue.fromS(currentWallet.getPartitionKey()),
                "SK", AttributeValue.fromS(PlayerWallet.SK_WALLET));

        // Condition checks balance >= softCurrencyCost AND version matches for optimistic locking
        Map<String, AttributeValue> purchaseValues = Map.of(
                ":cost", AttributeValue.fromN(String.valueOf(softCurrencyCost)),
                ":expectedVersion", AttributeValue.fromN(String.valueOf(currentWallet.getVersion())),
                ":one", AttributeValue.fromN("1"));

        // Wallet debit with funds guard and version check
        Update updateWallet = Update.builder()
                .tableName(tableName)
                .key(walletKey)
                .updateExpression(PURCHASE_UPDATE_EXPRESSION)
                .conditionExpression(PURCHASE_CONDITION)
                .expressionAttributeValues(purchaseValues)
                .build();

        // Purchase event put fails when the same event id was already written
        Map<String, AttributeValue> eventItem = EVENT_SCHEMA.itemToMap(purchaseEvent, true);

        Put putEvent = Put.builder()
                .tableName(gameEventsTableName)
                .item(eventItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        TransactWriteItem[] transactItems = new TransactWriteItem[WalletTransactItemOrder.values().length];
        transactItems[WalletTransactItemOrder.WALLET.index()] =
                TransactWriteItem.builder().update(updateWallet).build();
        transactItems[WalletTransactItemOrder.EVENT.index()] =
                TransactWriteItem.builder().put(putEvent).build();

        TransactWriteItemsRequest txRequest = TransactWriteItemsRequest.builder()
                .transactItems(transactItems)
                .build();

        return dynamoDbAsyncClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Purchase transaction completed [playerId={}]",
                        currentWallet.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses a raw {@code ADD currencyBalance :amount} expression so the increment is
     *           server-side atomic with no risk of overwrites from concurrent credits. The two items
     *           are ordered by {@link WalletTransactItemOrder}: the wallet ADD (guarded by
     *           {@code attribute_exists(PK)}) then the event PUT (guarded by
     *           {@code attribute_not_exists(PK)} for idempotency).
     */
    @Override
    public CompletableFuture<Void> earnCurrencyTransaction(String playerId, long amount,
                                                            GameEvent rewardEvent) {
        Map<String, AttributeValue> walletKey = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId),
                "SK", AttributeValue.fromS(PlayerWallet.SK_WALLET));

        Map<String, AttributeValue> earnValues = Map.of(
                ":amount", AttributeValue.fromN(String.valueOf(amount)));

        // Atomic ADD on currencyBalance, guarded only by item existence
        Update addCurrency = Update.builder()
                .tableName(tableName)
                .key(walletKey)
                .updateExpression(EARN_CURRENCY_EXPRESSION)
                .conditionExpression(EARN_WALLET_CONDITION)
                .expressionAttributeValues(earnValues)
                .build();

        // Idempotency guard rejects duplicate earn events via attribute_not_exists(PK)
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

        return dynamoDbAsyncClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Earn currency transaction completed [playerId={}]",
                        playerId));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Builds a {@code BatchGetItem} request with composite keys
     *           ({@code PK = USER#<id>}, {@code SK = PROFILE}) and uses
     *           {@link RetryHelper#executeBatchGetUntilComplete} to drain any unprocessed keys.
     */
    @Override
    public CompletableFuture<List<PlayerProfile>> batchGetPlayers(List<String> playerIds) {
        if (playerIds.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<Map<String, AttributeValue>> keys = playerIds.stream()
                .map(id -> Map.of(
                        "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + id),
                        "SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE)))
                .toList();

        BatchGetItemRequest request = BatchGetItemRequest.builder()
                .requestItems(Map.of(tableName, KeysAndAttributes.builder().keys(keys).build()))
                .build();

        return RetryHelper.executeBatchGetUntilComplete(
                        dynamoDbAsyncClient, request, RetryHelper.BATCH_GET_MAX_ROUNDS, RetryHelper.BATCH_GET_BASE_DELAY_MS)
                .thenApply(items -> items.stream()
                        .map(PLAYER_SCHEMA::mapToItem)
                        .toList());
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Queries {@code GSI_PLATFORM_PLAYERS} with {@code ScanIndexForward = false}
     *           so results come back ordered by {@code lastUpdatedAt} descending (most recent first).
     */
    @Override
    public CompletableFuture<List<PlayerProfile>> queryPlayersByPlatform(String platform, int limit) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName(PlayerProfile.GSI_PLATFORM_PLAYERS)
                .keyConditionExpression("platform = :platform")
                .expressionAttributeValues(Map.of(":platform", AttributeValue.fromS(platform)))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        return dynamoDbAsyncClient.query(request)
                .thenApply(response -> response.items().stream()
                        .map(PLAYER_SCHEMA::mapToItem)
                        .toList());
    }


    /**
     * {@inheritDoc}
     *
     * @implNote Uses {@code TransactWriteItems} with three conditional puts for PROFILE, SETTINGS, and WALLET.
     *           Sets {@code version = 1} on all items since there is no {@link VersionedRecordExtension}.
     */
    @Override
    public CompletableFuture<Void> createPlayerWithSettingsAndWallet(PlayerProfile profile,
                                                                      PlayerSettings settings,
                                                                      PlayerWallet wallet) {
        // No VersionedRecordExtension, so set initial version explicitly on all items
        profile.setVersion(1);
        settings.setVersion(1);
        wallet.setVersion(1);

        Map<String, AttributeValue> profileItem = PLAYER_SCHEMA.itemToMap(profile, true);
        Map<String, AttributeValue> settingsItem = SETTINGS_SCHEMA.itemToMap(settings, true);
        Map<String, AttributeValue> walletItem = WALLET_SCHEMA.itemToMap(wallet, true);

        Put putProfile = Put.builder()
                .tableName(tableName)
                .item(profileItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        Put putSettings = Put.builder()
                .tableName(tableName)
                .item(settingsItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        Put putWallet = Put.builder()
                .tableName(tableName)
                .item(walletItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        TransactWriteItemsRequest txRequest = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(putProfile).build(),
                        TransactWriteItem.builder().put(putSettings).build(),
                        TransactWriteItem.builder().put(putWallet).build())
                .build();

        return dynamoDbAsyncClient.transactWriteItems(txRequest)
                .thenRun(() -> logger.debug("Player registration transaction completed [playerId={}]",
                        profile.getPlayerId()));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<PlayerWallet> getWallet(String playerId) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId),
                "SK", AttributeValue.fromS(PlayerWallet.SK_WALLET));

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build();

        return dynamoDbAsyncClient.getItem(request)
                .thenApply(response -> {
                    if (!response.hasItem() || response.item().isEmpty()) {
                        return null;
                    }
                    return WALLET_SCHEMA.mapToItem(response.item());
                });
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<PlayerSettings> getSettings(String playerId) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + playerId),
                "SK", AttributeValue.fromS(PlayerSettings.SK_SETTINGS));

        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build();

        return dynamoDbAsyncClient.getItem(request)
                .thenApply(response -> {
                    if (!response.hasItem() || response.item().isEmpty()) {
                        return null;
                    }
                    return SETTINGS_SCHEMA.mapToItem(response.item());
                });
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses a low-level {@code UpdateItem} with explicit version check and increment.
     */
    @Override
    public CompletableFuture<PlayerSettings> updateSettings(PlayerSettings settings) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(settings.getPartitionKey()),
                "SK", AttributeValue.fromS(PlayerSettings.SK_SETTINGS));

        Map<String, AttributeValue> expressionValues = Map.of(
                ":notif", AttributeValue.fromBool(settings.isNotificationsEnabled()),
                ":lang", AttributeValue.fromS(settings.getPreferredLanguage()),
                ":vis", AttributeValue.fromS(settings.getProfileVisibility()),
                ":one", AttributeValue.fromN("1"),
                ":expectedVersion", AttributeValue.fromN(String.valueOf(settings.getVersion())));

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(UPDATE_SETTINGS_EXPRESSION)
                .conditionExpression(SETTINGS_CONDITION)
                .expressionAttributeValues(expressionValues)
                .returnValues(ReturnValue.ALL_NEW)
                .build();

        return dynamoDbAsyncClient.updateItem(request)
                .thenApply(response -> SETTINGS_SCHEMA.mapToItem(response.attributes()));
    }
}
