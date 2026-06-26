package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbPlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.WalletTransactItemOrder;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

/**
 * Unit tests for {@link LowLevelDynamoDbPlayerStateRepository} with mocked client.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LowLevelDynamoDbPlayerStateRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void createPlayer_whenValidProfile_shouldSendConditionalPutItem() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        PlayerProfile profile = new PlayerProfile();
        profile.setPartitionKey(PlayerProfile.PK_PREFIX + "p1");
        profile.setSortKey(PlayerProfile.SK_PROFILE);
        profile.setPlayerId("p1");

        repository.createPlayer(profile).join();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression()).contains("attribute_not_exists");
        assertThat(captor.getValue().tableName()).isEqualTo("PlayerState");
    }

    @Test
    void getPlayer_whenPlayerMissing_shouldReturnNull() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        assertThat(repository.getPlayer("unknown").join()).isNull();
    }

    @Test
    void getPlayer_whenPlayerPresent_shouldMapItem() {
        PlayerProfile stored = new PlayerProfile();
        stored.setPlayerId("p9");
        stored.setPartitionKey(PlayerProfile.PK_PREFIX + "p9");
        stored.setSortKey(PlayerProfile.SK_PROFILE);
        Map<String, AttributeValue> item = TableSchema.fromBean(PlayerProfile.class).itemToMap(stored, false);

        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        PlayerProfile result = repository.getPlayer("p9").join();
        assertThat(result).isNotNull();
        assertThat(result.getPlayerId()).isEqualTo("p9");
    }

    @Test
    void updateProgression_whenValidPatch_shouldSendUpdateItemWithVersionCondition() {
        PlayerProfile updated = new PlayerProfile();
        updated.setPlayerId("p9");
        updated.setPartitionKey(PlayerProfile.PK_PREFIX + "p9");
        updated.setSortKey(PlayerProfile.SK_PROFILE);
        updated.setVersion(3);
        Map<String, AttributeValue> attrs =
                TableSchema.fromBean(PlayerProfile.class).itemToMap(updated, false);

        when(client.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().attributes(attrs).build()));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        PlayerProfile result = repository.updateProgression("p9", 3500L, 4, 2L).join();

        assertThat(result.getPlayerId()).isEqualTo("p9");
        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client).updateItem(captor.capture());
        UpdateItemRequest sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo("PlayerState");
        assertThat(sent.updateExpression()).contains(":txp").contains("totalExperience");
        assertThat(sent.conditionExpression()).contains("version");
        assertThat(sent.expressionAttributeValues().get(":txp").n()).isEqualTo("3500");
        assertThat(sent.expressionAttributeValues().get(":expectedVersion").n()).isEqualTo("2");
    }

    @Test
    void purchaseTransaction_whenValidRequest_shouldSendTransactWriteWithConditionalPut() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEventsTable");

        PlayerWallet wallet = new PlayerWallet();
        wallet.setPartitionKey(PlayerProfile.PK_PREFIX + "p1");
        wallet.setSortKey(PlayerWallet.SK_WALLET);
        wallet.setPlayerId("p1");
        wallet.setCurrencyBalance(800L);
        wallet.setVersion(4L);

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + "p1");
        event.setSortKey(GameEvent.SK_PREFIX + "2026-01-01T00:00:00Z#evt-gem");
        event.setEventId("evt-gem");
        event.setPlayerId("p1");

        repository.purchaseTransaction(wallet, 150L, "gem", event).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems()).hasSize(2);
        Update update = captor.getValue().transactItems().get(WalletTransactItemOrder.WALLET.index()).update();
        assertThat(update.tableName()).isEqualTo("PlayerState");
        assertThat(update.conditionExpression()).contains("currencyBalance").contains("version");
        var eventPut = captor.getValue().transactItems().get(WalletTransactItemOrder.EVENT.index()).put();
        assertThat(eventPut.tableName()).isEqualTo("GameEventsTable");
        assertThat(eventPut.conditionExpression()).contains("attribute_not_exists");
    }

    @Test
    void earnCurrencyTransaction_whenValidRequest_shouldOrderWalletThenEvent() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEventsTable");

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + "p1");
        event.setSortKey(GameEvent.SK_PREFIX + "2026-01-01T00:00:00Z#evt-earn");
        event.setEventId("evt-earn");
        event.setPlayerId("p1");

        PlayerWallet wallet = new PlayerWallet();
        wallet.setPartitionKey(PlayerProfile.PK_PREFIX + "p1");
        wallet.setSortKey(PlayerWallet.SK_WALLET);
        wallet.setPlayerId("p1");
        wallet.setCurrencyBalance(1000L);
        wallet.setVersion(1L);

        repository.earnCurrencyTransaction(wallet, 300L, event).join();

        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(captor.capture());
        assertThat(captor.getValue().transactItems()).hasSize(2);
        Update walletCredit = captor.getValue().transactItems().get(WalletTransactItemOrder.WALLET.index()).update();
        assertThat(walletCredit.tableName()).isEqualTo("PlayerState");
        assertThat(walletCredit.updateExpression()).contains("currencyBalance").contains("version");
        assertThat(walletCredit.conditionExpression()).contains("version");
        var eventPut = captor.getValue().transactItems().get(WalletTransactItemOrder.EVENT.index()).put();
        assertThat(eventPut.tableName()).isEqualTo("GameEventsTable");
        assertThat(eventPut.conditionExpression()).contains("attribute_not_exists");
    }

    @Test
    void batchGetPlayers_whenNoIdsProvided_shouldReturnEmpty() {
        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        assertThat(repository.batchGetPlayers(List.of()).join()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void batchGetPlayers_whenIdsProvided_shouldSendBatchGetItemRequest() {
        PlayerProfile stored = profileForBatchGet("bob");
        TableSchema<PlayerProfile> schema = TableSchema.fromBean(PlayerProfile.class);
        Map<String, AttributeValue> item = schema.itemToMap(stored, false);

        BatchGetItemResponse response = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(item)))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        List<PlayerProfile> result = repository.batchGetPlayers(List.of("bob")).join();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPlayerId()).isEqualTo("bob");

        ArgumentCaptor<BatchGetItemRequest> captor = ArgumentCaptor.forClass(BatchGetItemRequest.class);
        verify(client).batchGetItem(captor.capture());
        assertThat(captor.getValue().requestItems().get("PlayerState").keys()).hasSize(1);
        assertThat(captor.getValue().requestItems().get("PlayerState").keys().getFirst().get("PK").s())
                .isEqualTo(PlayerProfile.PK_PREFIX + "bob");
        assertThat(captor.getValue().requestItems().get("PlayerState").keys().getFirst().get("SK").s())
                .isEqualTo(PlayerProfile.SK_PROFILE);
    }

    @Test
    void batchGetPlayers_whenUnprocessedKeysRemain_shouldRetryUnprocessedKeys() {
        TableSchema<PlayerProfile> schema = TableSchema.fromBean(PlayerProfile.class);
        PlayerProfile first = profileForBatchGet("id-a");
        PlayerProfile second = profileForBatchGet("id-b");

        Map<String, AttributeValue> keyB = Map.of(
                "PK", AttributeValue.fromS(PlayerProfile.PK_PREFIX + "id-b"),
                "SK", AttributeValue.fromS(PlayerProfile.SK_PROFILE));

        BatchGetItemResponse partial = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(schema.itemToMap(first, false))))
                .unprocessedKeys(Map.of("PlayerState", KeysAndAttributes.builder()
                        .keys(List.of(keyB))
                        .build()))
                .build();

        BatchGetItemResponse complete = BatchGetItemResponse.builder()
                .responses(Map.of("PlayerState", List.of(schema.itemToMap(second, false))))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(partial))
                .thenReturn(CompletableFuture.completedFuture(complete));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        List<PlayerProfile> result = repository.batchGetPlayers(List.of("id-a", "id-b")).join();

        assertThat(result).hasSize(2);
        verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void queryPlayersByPlatform_whenPlatformProvided_shouldQueryGsiWithCorrectExpression() {
        PlayerProfile stored = profileForBatchGet("browse-one");
        TableSchema<PlayerProfile> schema = TableSchema.fromBean(PlayerProfile.class);
        Map<String, AttributeValue> item = schema.itemToMap(stored, false);

        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of(item)).build()));

        LowLevelDynamoDbPlayerStateRepository repository =
                new LowLevelDynamoDbPlayerStateRepository(client, "PlayerState", "GameEvents");

        List<PlayerProfile> result = repository.queryPlayersByPlatform("IOS", 12).join();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPlayerId()).isEqualTo("browse-one");

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        QueryRequest sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo("PlayerState");
        assertThat(sent.indexName()).isEqualTo(PlayerProfile.GSI_PLATFORM_PLAYERS);
        assertThat(sent.keyConditionExpression()).isEqualTo("platform = :platform");
        assertThat(sent.scanIndexForward()).isFalse();
        assertThat(sent.limit()).isEqualTo(12);
        assertThat(sent.expressionAttributeValues().get(":platform").s()).isEqualTo("IOS");
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
