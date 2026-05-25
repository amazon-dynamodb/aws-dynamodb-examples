package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Low-level {@link DynamoDbAsyncClient} implementation of {@link GameEventRepository}.
 *
 * <p>Uses {@link TableSchema#fromBean(Class)} for marshalling between {@link GameEvent}
 * beans and DynamoDB item maps, while issuing requests through the low-level async client
 * for full control over key-condition expressions and pagination cursors.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "low-level")
public class LowLevelDynamoDbGameEventRepository implements GameEventRepository {

    private static final Logger logger = LoggerFactory.getLogger(LowLevelDynamoDbGameEventRepository.class);

    /** Bean schema for marshalling {@link GameEvent}. */
    private static final TableSchema<GameEvent> EVENT_SCHEMA = TableSchema.fromBean(GameEvent.class);

    /** Low-level async DynamoDB dynamoDbAsyncClient. */
    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    /** Physical GameEvents table name. */
    private final String tableName;

    /**
     * Creates the repository.
     *
     * @param dynamoDbAsyncClient the low-level DynamoDB async client
     * @param tableName GameEvents table name
     */
    public LowLevelDynamoDbGameEventRepository(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${dynamodb.game-events-table-name}") String tableName) {
        this.dynamoDbAsyncClient = dynamoDbAsyncClient;
        this.tableName = tableName;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Marshals the event with {@link TableSchema#itemToMap(Object, boolean)} and issues
     *           an unconditional low-level {@code PutItem}.
     */
    @Override
    public CompletableFuture<Void> appendEvent(GameEvent event) {
        Map<String, AttributeValue> item = EVENT_SCHEMA.itemToMap(event, true);

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        return dynamoDbAsyncClient.putItem(request)
                .thenRun(() -> logger.debug("Game event appended [eventId={}, playerId={}]",
                        event.getEventId(), event.getPlayerId()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Queries {@code PK = USER#<playerId>} with {@code begins_with(SK, EVENT#)}.
     *           Passes through {@code scanIndexForward} and {@code exclusiveStartKey} for sort
     *           order and pagination.
     */
    @Override
    public CompletableFuture<GameEventPage> queryEventsByPlayer(String playerId, int limit, boolean scanIndexForward,
                                                                 Map<String, AttributeValue> exclusiveStartKey) {
        // Key condition: partition match + begins_with on SK filters to EVENT# items only
        Map<String, AttributeValue> expressionValues = Map.of(
                ":pk", AttributeValue.fromS(GameEvent.PK_PREFIX + playerId),
                ":skPrefix", AttributeValue.fromS(GameEvent.SK_PREFIX));

        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND begins_with(SK, :skPrefix)")
                .expressionAttributeValues(expressionValues)
                .scanIndexForward(scanIndexForward)
                .limit(limit);

        if (exclusiveStartKey != null && !exclusiveStartKey.isEmpty()) {
            builder.exclusiveStartKey(exclusiveStartKey);
        }

        return dynamoDbAsyncClient.query(builder.build())
                .thenApply(response -> {
                    List<GameEvent> events = response.items().stream()
                            .map(EVENT_SCHEMA::mapToItem)
                            .toList();

                    Map<String, AttributeValue> lastKey = response.hasLastEvaluatedKey()
                            ? response.lastEvaluatedKey()
                            : null;

                    return new GameEventPage(events, lastKey);
                });
    }
}
