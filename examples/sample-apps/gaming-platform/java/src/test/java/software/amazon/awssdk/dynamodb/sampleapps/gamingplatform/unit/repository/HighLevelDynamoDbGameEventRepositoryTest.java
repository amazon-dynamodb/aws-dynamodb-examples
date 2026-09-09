package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventPage;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.HighLevelDynamoDbGameEventRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link HighLevelDynamoDbGameEventRepository} with mocked clients.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class HighLevelDynamoDbGameEventRepositoryTest {

    private static final TableSchema<GameEvent> EVENT_SCHEMA = TableSchema.fromBean(GameEvent.class);

    /**
     * @return a {@link PagePublisher} that emits one empty {@link Page} (for stubs)
     */
    private static PagePublisher<GameEvent> singleEmptyPagePublisher() {
        Page<GameEvent> page = Page.create(List.of(), null);
        return PagePublisher.create(SdkPublisher.fromIterable(List.of(page)));
    }

    @Mock
    private DynamoDbEnhancedAsyncClient enhancedClient;

    @Mock
    private DynamoDbAsyncTable<GameEvent> eventTable;

    private HighLevelDynamoDbGameEventRepository repository;

    /**
     * Wires the repository under test with a mocked enhanced client that returns
     * {@code eventTable} on the first {@code table()} call.
     */
    @BeforeEach
    void setUp() {
        AtomicInteger tableCalls = new AtomicInteger(0);
        when(enhancedClient.table(any(), any(TableSchema.class)))
                .thenAnswer(inv -> {
                    if (tableCalls.getAndIncrement() == 0) {
                        return eventTable;
                    }
                    throw new IllegalStateException("unexpected table() call");
                });
        repository = new HighLevelDynamoDbGameEventRepository(enhancedClient, "GameEvents");
    }

    @Test
    void appendEvent_whenValidEvent_shouldCallPutItemOnTable() {
        when(eventTable.putItem(any(GameEvent.class))).thenReturn(CompletableFuture.completedFuture(null));

        GameEvent event = new GameEvent();
        event.setEventId("e1");
        event.setPlayerId("p1");

        repository.appendEvent(event).join();

        verify(eventTable).putItem(event);
    }

    @Test
    void queryEventsByPlayer_whenPlayerIdProvided_shouldUseEnhancedTableQuery() {
        when(eventTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(singleEmptyPagePublisher());

        GameEventPage page = repository.queryEventsByPlayer("pid", 10, false, null).join();

        assertThat(page.events()).isEmpty();
        assertThat(page.lastEvaluatedKey()).isNull();

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(eventTable).query(captor.capture());
        QueryEnhancedRequest sent = captor.getValue();
        assertThat(sent.limit()).isEqualTo(10);
        assertThat(sent.scanIndexForward()).isFalse();
        Expression expr = sent.queryConditional().expression(EVENT_SCHEMA, TableMetadata.primaryIndexName());
        assertThat(expr.expression()).contains("begins_with");
        assertThat(expr.expressionValues().values())
                .extracting(AttributeValue::s)
                .contains(GameEvent.PK_PREFIX + "pid", GameEvent.SK_PREFIX);
    }

    @Test
    void queryEventsByPlayer_whenStartKeyPresent_shouldPassExclusiveStartKey() {
        Map<String, AttributeValue> start = Map.of("PK", AttributeValue.fromS("x"));
        when(eventTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(singleEmptyPagePublisher());

        repository.queryEventsByPlayer("pid", 5, false, start).join();

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(eventTable).query(captor.capture());
        assertThat(captor.getValue().exclusiveStartKey()).isEqualTo(start);
        assertThat(captor.getValue().limit()).isEqualTo(5);
    }

    @Test
    void queryEventsByPlayer_whenScanIndexForwardTrue_shouldSetAscendingOnQuery() {
        when(eventTable.query(any(QueryEnhancedRequest.class)))
                .thenReturn(singleEmptyPagePublisher());

        repository.queryEventsByPlayer("pid", 4, true, null).join();

        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(eventTable).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isTrue();
        assertThat(captor.getValue().limit()).isEqualTo(4);
    }
}
