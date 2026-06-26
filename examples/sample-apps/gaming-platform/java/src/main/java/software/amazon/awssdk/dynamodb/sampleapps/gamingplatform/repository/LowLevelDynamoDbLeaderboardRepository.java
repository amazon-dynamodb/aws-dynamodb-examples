package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Low-level {@link DynamoDbAsyncClient} implementation of {@link LeaderboardRepository}.
 *
 * <p>Uses {@link TableSchema#fromBean(Class)} to marshal and unmarshal {@link LeaderboardEntry}
 * beans while issuing requests through the low-level async client for full control over
 * query expressions and sort order.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "low-level")
public class LowLevelDynamoDbLeaderboardRepository implements LeaderboardRepository {

    private static final Logger logger = LoggerFactory.getLogger(LowLevelDynamoDbLeaderboardRepository.class);

    /** Bean schema for mapping {@link LeaderboardEntry} to item maps. */
    private static final TableSchema<LeaderboardEntry> SCHEMA = TableSchema.fromBean(LeaderboardEntry.class);

    /** Low-level async DynamoDB dynamoDbAsyncClient. */
    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    /** Configured physical table name. */
    private final String tableName;

    /**
     * Creates the repository.
     *
     * @param dynamoDbAsyncClient the low-level DynamoDB async client
     * @param tableName Leaderboard table name
     */
    public LowLevelDynamoDbLeaderboardRepository(
            DynamoDbAsyncClient dynamoDbAsyncClient,
            @Value("${dynamodb.leaderboard-table-name}") String tableName) {
        this.dynamoDbAsyncClient = dynamoDbAsyncClient;
        this.tableName = tableName;
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Marshals the entry and issues an idempotent low-level {@code PutItem}.
     */
    @Override
    public CompletableFuture<Void> putLeaderboardEntry(LeaderboardEntry entry) {
        Map<String, AttributeValue> item = SCHEMA.itemToMap(entry, true);

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        return dynamoDbAsyncClient.putItem(request)
                .thenRun(() -> logger.debug("Leaderboard entry stored [partitionKey={}, sortKey={}]",
                        entry.getPartitionKey(), entry.getSortKey()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Deletes by exact {@code PK} and {@code SK} through a low-level {@code DeleteItem}.
     */
    @Override
    public CompletableFuture<Void> deleteLeaderboardEntry(String partitionKey, String sortKey) {
        Map<String, AttributeValue> key = Map.of(
                "PK", AttributeValue.fromS(partitionKey),
                "SK", AttributeValue.fromS(sortKey));

        DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key)
                .build();

        return dynamoDbAsyncClient.deleteItem(request)
                .thenRun(() -> logger.debug("Leaderboard entry deleted [partitionKey={}, sortKey={}]",
                        partitionKey, sortKey));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Queries with {@code ScanIndexForward = false} so that sort keys (zero-padded scores)
     *           come back in descending order, giving the top-scoring entries first.
     */
    @Override
    public CompletableFuture<List<LeaderboardEntry>> queryTopN(String scope, int limit) {
        String partitionKey = LeaderboardEntry.buildPartitionKey(scope);

        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(partitionKey)))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        return dynamoDbAsyncClient.query(request)
                .thenApply(response -> response.items().stream()
                        .map(SCHEMA::mapToItem)
                        .toList());
    }
}
