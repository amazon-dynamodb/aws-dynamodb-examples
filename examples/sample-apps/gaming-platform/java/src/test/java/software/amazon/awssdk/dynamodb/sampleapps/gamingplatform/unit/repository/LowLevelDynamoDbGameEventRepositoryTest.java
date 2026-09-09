package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventPage;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbGameEventRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Unit tests for {@link LowLevelDynamoDbGameEventRepository} with mocked client.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LowLevelDynamoDbGameEventRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void appendEvent_whenValidEvent_shouldSendPutItem() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbGameEventRepository repository =
                new LowLevelDynamoDbGameEventRepository(client, "GameEvents");

        GameEvent event = new GameEvent();
        event.setEventId("e1");
        event.setPlayerId("p1");
        event.setPartitionKey(GameEvent.PK_PREFIX + "p1");
        event.setSortKey(GameEvent.SK_PREFIX + "ts#e1");
        event.setEntityType(GameEvent.ENTITY_TYPE);
        event.setEventType("LOGIN");
        event.setRecordedAt("2026-01-01T00:00:00Z");

        repository.appendEvent(event).join();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("GameEvents");
        assertThat(captor.getValue().item()).containsKey("PK");
    }

    @Test
    void queryEventsByPlayer_whenPlayerIdProvided_shouldReturnPageFromQuery() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        LowLevelDynamoDbGameEventRepository repository =
                new LowLevelDynamoDbGameEventRepository(client, "GameEvents");

        GameEventPage page = repository.queryEventsByPlayer("p", 10, false, null).join();
        assertThat(page.events()).isEmpty();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        QueryRequest sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo("GameEvents");
        assertThat(sent.limit()).isEqualTo(10);
        assertThat(sent.scanIndexForward()).isFalse();
        assertThat(sent.expressionAttributeValues().get(":pk").s()).isEqualTo(GameEvent.PK_PREFIX + "p");
    }

    @Test
    void queryEventsByPlayer_whenStartKeyProvided_shouldSetExclusiveStartKey() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        LowLevelDynamoDbGameEventRepository repository =
                new LowLevelDynamoDbGameEventRepository(client, "GameEvents");
        Map<String, AttributeValue> esk = Map.of("PK", AttributeValue.fromS("k"));

        repository.queryEventsByPlayer("p", 3, false, esk).join();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().exclusiveStartKey()).isEqualTo(esk);
    }

    @Test
    void queryEventsByPlayer_whenScanIndexForwardTrue_shouldSendAscendingQuery() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        QueryResponse.builder().items(List.of()).build()));

        LowLevelDynamoDbGameEventRepository repository =
                new LowLevelDynamoDbGameEventRepository(client, "GameEvents");

        repository.queryEventsByPlayer("p", 7, true, null).join();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isTrue();
        assertThat(captor.getValue().limit()).isEqualTo(7);
    }
}
