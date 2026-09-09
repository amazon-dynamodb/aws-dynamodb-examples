package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.HighLevelDynamoDbPlayerStateRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

/**
 * Unit tests for {@link HighLevelDynamoDbPlayerStateRepository} with mocked clients.
 *
 * <p>Verifies player, wallet, settings, batch-get, and platform GSI query paths against stubbed
 * enhanced tables and indexes.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class HighLevelDynamoDbPlayerStateRepositoryTest {

    @Mock
    private DynamoDbEnhancedAsyncClient enhancedClient;

    @Mock
    private DynamoDbAsyncClient dynamoDbClient;

    @Mock
    private DynamoDbAsyncTable<PlayerProfile> playerTable;

    @Mock
    private DynamoDbAsyncTable<GameEvent> gameEventsTable;

    @Mock
    private DynamoDbAsyncTable<PlayerWallet> walletTable;

    @Mock
    private DynamoDbAsyncIndex<PlayerProfile> platformIndex;

    private HighLevelDynamoDbPlayerStateRepository repository;

    /**
     * Wires mocked tables with the repository under test. The enhanced client stub returns
     * tables in declaration order: profile, settings, wallet, and game events.
     */
    @BeforeEach
    void setUp() {
        AtomicInteger idx = new AtomicInteger(0);
        when(enhancedClient.table(anyString(), any(TableSchema.class)))
                .thenAnswer(inv -> {
                    int i = idx.getAndIncrement();
                    return switch (i) {
                        case 0 -> playerTable;   // PlayerProfile schema
                        case 1 -> playerTable;   // PlayerSettings schema (same physical table mock)
                        case 2 -> walletTable;   // PlayerWallet schema
                        default -> gameEventsTable; // GameEvent schema
                    };
                });
        lenient().when(playerTable.tableName()).thenReturn("PlayerState");
        lenient().when(walletTable.tableName()).thenReturn("PlayerState");
        lenient().when(gameEventsTable.tableName()).thenReturn("GameEvents");
        lenient().when(playerTable.tableSchema()).thenReturn(TableSchema.fromBean(PlayerProfile.class));
        lenient().when(walletTable.tableSchema()).thenReturn(TableSchema.fromBean(PlayerWallet.class));
        lenient().when(gameEventsTable.tableSchema()).thenReturn(TableSchema.fromBean(GameEvent.class));
        lenient().when(playerTable.index(PlayerProfile.GSI_PLATFORM_PLAYERS)).thenReturn(platformIndex);
        repository = new HighLevelDynamoDbPlayerStateRepository(enhancedClient, dynamoDbClient, "PlayerState", "GameEvents");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPlayer_whenValidProfile_shouldSendConditionalPut() {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId("p1");
        when(playerTable.putItem(any(PutItemEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        repository.createPlayer(profile).join();

        ArgumentCaptor<PutItemEnhancedRequest<PlayerProfile>> captor =
                ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
        verify(playerTable).putItem(captor.capture());
        PutItemEnhancedRequest<PlayerProfile> sent = captor.getValue();
        assertThat(sent.item().getPlayerId()).isEqualTo("p1");
        assertThat(sent.conditionExpression().expression()).contains("attribute_not_exists");
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    void getPlayer_whenKeyProvided_shouldLoadByKey() {
        PlayerProfile loaded = new PlayerProfile();
        loaded.setPlayerId("p1");
        when(playerTable.getItem(any(Key.class))).thenReturn(CompletableFuture.completedFuture(loaded));

        PlayerProfile result = repository.getPlayer("p1").join();

        assertThat(result).isSameAs(loaded);
        ArgumentCaptor<Key> keyCaptor = ArgumentCaptor.forClass(Key.class);
        verify(playerTable).getItem(keyCaptor.capture());
        assertThat(keyCaptor.getValue().partitionKeyValue().s()).isEqualTo(PlayerProfile.PK_PREFIX + "p1");
        assertThat(keyCaptor.getValue().sortKeyValue().get().s()).isEqualTo(PlayerProfile.SK_PROFILE);
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateProgression_whenValidPatch_shouldUseEnhancedUpdateItemWithVersioning() {
        PlayerProfile updated = new PlayerProfile();
        updated.setPlayerId("p1");
        updated.setVersion(8);
        when(playerTable.updateItem(any(UpdateItemEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(updated));

        PlayerProfile result = repository.updateProgression("p1", 1500L, 3, 7L).join();

        assertThat(result).isSameAs(updated);
        ArgumentCaptor<UpdateItemEnhancedRequest<PlayerProfile>> captor =
                ArgumentCaptor.forClass(UpdateItemEnhancedRequest.class);
        verify(playerTable).updateItem(captor.capture());
        PlayerProfile sent = captor.getValue().item();
        assertThat(sent.getPartitionKey()).isEqualTo(PlayerProfile.PK_PREFIX + "p1");
        assertThat(sent.getSortKey()).isEqualTo(PlayerProfile.SK_PROFILE);
        assertThat(sent.getTotalExperience()).isEqualTo(1500L);
        assertThat(sent.getCurrentLevel()).isEqualTo(3);
        assertThat(sent.getVersion()).isEqualTo(7L);
        assertThat(captor.getValue().returnValues()).isEqualTo(ReturnValue.ALL_NEW);
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    void purchaseTransaction_whenValidRequest_shouldUseEnhancedTransactWriteItems() {
        when(enhancedClient.transactWriteItems(any(TransactWriteItemsEnhancedRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        PlayerWallet wallet = new PlayerWallet();
        wallet.setPartitionKey(PlayerProfile.PK_PREFIX + "p1");
        wallet.setSortKey(PlayerWallet.SK_WALLET);
        wallet.setPlayerId("p1");
        wallet.setCurrencyBalance(1000L);
        wallet.setVersion(5L);

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + "p1");
        event.setSortKey(GameEvent.SK_PREFIX + "2026-01-01T00:00:00Z#purchase-evt");
        event.setEventId("purchase-evt");

        repository.purchaseTransaction(wallet, 200L, "cosmetic-a", event).join();

        ArgumentCaptor<TransactWriteItemsEnhancedRequest> txCaptor =
                ArgumentCaptor.forClass(TransactWriteItemsEnhancedRequest.class);
        verify(enhancedClient).transactWriteItems(txCaptor.capture());
        List<TransactWriteItem> items = txCaptor.getValue().transactWriteItems();
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().update()).isNotNull();
        assertThat(items.getFirst().update().tableName()).isEqualTo("PlayerState");
        assertThat(items.getFirst().update().conditionExpression()).contains("currencyBalance");
        assertThat(items.get(1).put()).isNotNull();
        assertThat(items.get(1).put().tableName()).isEqualTo("GameEvents");
        assertThat(items.get(1).put().conditionExpression()).contains("attribute_not_exists");
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    void batchGetPlayers_whenNoIdsProvided_shouldReturnEmpty() {
        assertThat(repository.batchGetPlayers(List.of()).join()).isEmpty();
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    void batchGetPlayers_whenProfilesExist_shouldReturnFoundProfiles() {
        PlayerProfile stored = profileForBatchGet("alice");
        Map<String, AttributeValue> item = TableSchema.fromBean(PlayerProfile.class).itemToMap(stored, false);

        BatchGetItemResponse response = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(item)))
                .build();

        BatchGetResultPage page = BatchGetResultPage.builder().batchGetItemResponse(response).build();
        when(enhancedClient.batchGetItem(any(BatchGetItemEnhancedRequest.class)))
                .thenReturn(BatchGetResultPagePublisher.create(SdkPublisher.fromIterable(List.of(page))));

        List<PlayerProfile> result = repository.batchGetPlayers(List.of("alice")).join();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPlayerId()).isEqualTo("alice");

        ArgumentCaptor<BatchGetItemEnhancedRequest> captor = ArgumentCaptor.forClass(BatchGetItemEnhancedRequest.class);
        verify(enhancedClient).batchGetItem(captor.capture());
        assertThat(captor.getValue().readBatches()).hasSize(1);
        assertThat(captor.getValue().readBatches().iterator().next().keysAndAttributes().keys()).hasSize(1);
        Map<String, AttributeValue> key = captor.getValue().readBatches().iterator().next().keysAndAttributes().keys().getFirst();
        assertThat(key.get("PK").s()).isEqualTo(PlayerProfile.PK_PREFIX + "alice");
        assertThat(key.get("SK").s()).isEqualTo(PlayerProfile.SK_PROFILE);
    }

    @Test
    void batchGetPlayers_whenUnprocessedKeysRemain_shouldRetryUnprocessedKeys() {
        PlayerProfile first = profileForBatchGet("p-one");
        PlayerProfile second = profileForBatchGet("p-two");
        TableSchema<PlayerProfile> schema = TableSchema.fromBean(PlayerProfile.class);

        Map<String, AttributeValue> key2 = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + "p-two"),
                "SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE));

        BatchGetItemResponse partial = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(schema.itemToMap(first, false))))
                .unprocessedKeys(Map.of("PlayerState", KeysAndAttributes.builder()
                        .keys(List.of(key2))
                        .build()))
                .build();

        BatchGetItemResponse complete = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(schema.itemToMap(second, false))))
                .build();

        BatchGetResultPage page1 = BatchGetResultPage.builder().batchGetItemResponse(partial).build();
        BatchGetResultPage page2 = BatchGetResultPage.builder().batchGetItemResponse(complete).build();
        when(enhancedClient.batchGetItem(any(BatchGetItemEnhancedRequest.class)))
                .thenReturn(BatchGetResultPagePublisher.create(SdkPublisher.fromIterable(List.of(page1))))
                .thenReturn(BatchGetResultPagePublisher.create(SdkPublisher.fromIterable(List.of(page2))));

        List<PlayerProfile> result = repository.batchGetPlayers(List.of("p-one", "p-two")).join();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(PlayerProfile::getPlayerId).containsExactlyInAnyOrder("p-one", "p-two");
        verify(enhancedClient, times(2)).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    @Test
    void queryPlayersByPlatform_whenPlatformProvided_shouldQueryGsiWithLimit() {
        PlayerProfile row = profileForBatchGet("gsi-player");
        PagePublisher<PlayerProfile> publisher =
                PagePublisher.create(SdkPublisher.fromIterable(List.of(Page.create(List.of(row)))));
        when(platformIndex.query(any(QueryEnhancedRequest.class))).thenReturn(publisher);

        List<PlayerProfile> result = repository.queryPlayersByPlatform("PC", 7).join();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPlayerId()).isEqualTo("gsi-player");

        ArgumentCaptor<QueryEnhancedRequest> queryCaptor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(platformIndex).query(queryCaptor.capture());
        QueryEnhancedRequest sent = queryCaptor.getValue();
        assertThat(sent.limit()).isEqualTo(7);
        assertThat(sent.scanIndexForward()).isFalse();
        verify(enhancedClient, never()).batchGetItem(any(BatchGetItemEnhancedRequest.class));
    }

    /**
     * Builds a minimal {@link PlayerProfile} fixture suitable for batch-get assertions.
     *
     * @param playerId player identifier
     * @return populated profile
     */
    private static PlayerProfile profileForBatchGet(String playerId) {
        PlayerProfile p = new PlayerProfile();
        p.setPlayerId(playerId);
        p.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        p.setSortKey(PlayerProfile.SK_PROFILE);
        p.setEntityType(PlayerProfile.ENTITY_TYPE);
        p.setPlatform("PC");
        p.setPlatformUserId("u-" + playerId);
        p.setPlayerName("Name-" + playerId);
        p.setCurrentLevel(1);
        p.setTotalExperience(0L);
        p.setVersion(1L);
        p.setLastUpdatedAt("2026-01-01T00:00:00Z");
        return p;
    }
}
