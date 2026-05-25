package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * High-level {@link DynamoDbEnhancedAsyncClient} implementation of {@link GameEventRepository}.
 *
 * <p>Uses the enhanced client's type-safe {@link DynamoDbAsyncTable} API for unconditional
 * {@code PutItem} and for paginated {@code Query} built with {@link QueryEnhancedRequest}.
 * Pagination cursors come from {@link Page#lastEvaluatedKey()}, which matches the low-level
 * client's {@code LastEvaluatedKey} map shape for the same gameEventsTable.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
public class HighLevelDynamoDbGameEventRepository implements GameEventRepository {

    private static final Logger logger = LoggerFactory.getLogger(HighLevelDynamoDbGameEventRepository.class);

    /** Bean schema for {@link GameEvent} table mapping. */
    private static final TableSchema<GameEvent> EVENT_SCHEMA = TableSchema.fromBean(GameEvent.class);

    /** Typed table for {@code PutItem} and {@code Query}. */
    private final DynamoDbAsyncTable<GameEvent> gameEventsTable;

    /**
     * Creates the repository.
     *
     * @param enhancedClient the high-level enhanced async client
     * @param tableName      GameEvents table name
     */
    public HighLevelDynamoDbGameEventRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${dynamodb.game-events-table-name}") String tableName) {
        this.gameEventsTable = enhancedClient.table(tableName, EVENT_SCHEMA);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> appendEvent(GameEvent event) {
        return gameEventsTable.putItem(event)
                .thenRun(() -> logger.debug("Game event appended [eventId={}, playerId={}]",
                        event.getEventId(), event.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Consumes the first {@link Page} from the enhanced client's reactive query stream
     *           then cancels the subscription so one HTTP page maps to one DynamoDB query page.
     */
    @Override
    public CompletableFuture<GameEventPage> queryEventsByPlayer(String playerId, int limit, boolean scanIndexForward,
                                                                 Map<String, AttributeValue> exclusiveStartKey) {
        // sortBeginsWith restricts to EVENT# prefixed sort keys under the player partition
        QueryEnhancedRequest.Builder requestBuilder = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.sortBeginsWith(
                        Key.builder()
                                .partitionValue(GameEvent.PK_PREFIX + playerId)
                                .sortValue(GameEvent.SK_PREFIX)
                                .build()))
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
            requestBuilder.exclusiveStartKey(exclusiveStartKey);
        }

        QueryEnhancedRequest queryRequest = requestBuilder.build();

        // Subscribe to the reactive page publisher and consume only the first page
        CompletableFuture<GameEventPage> result = new CompletableFuture<>();
        gameEventsTable.query(queryRequest).subscribe(new Subscriber<Page<GameEvent>>() {
            /** Upstream subscription handle used to request pages and cancel the stream. */
            private Subscription subscription;

            /** @param s upstream subscription */
            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            /** @param page first (and only consumed) query page */
            @Override
            public void onNext(Page<GameEvent> page) {
                List<GameEvent> events = page.items();
                // Normalize empty map to null so callers see null as "no more pages"
                Map<String, AttributeValue> rawLastKey = page.lastEvaluatedKey();
                Map<String, AttributeValue> lastKey =
                        rawLastKey == null || rawLastKey.isEmpty() ? null : rawLastKey;
                result.complete(new GameEventPage(events, lastKey));
                subscription.cancel();
            }

            /** @param t failure from the publisher */
            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            /** Completes with an empty page if the publisher finishes without emitting. */
            @Override
            public void onComplete() {
                if (!result.isDone()) {
                    result.complete(new GameEventPage(List.of(), null));
                }
            }
        });

        return result;
    }
}
