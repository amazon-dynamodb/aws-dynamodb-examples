package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

/**
 * High-level {@link DynamoDbEnhancedAsyncClient} implementation of {@link LeaderboardRepository}.
 *
 * <p>Uses the enhanced client's type-safe {@link DynamoDbAsyncTable} API for put, delete,
 * and query operations on the LeaderboardAggregate leaderboardTable.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
public class HighLevelDynamoDbLeaderboardRepository implements LeaderboardRepository {

    private static final Logger logger = LoggerFactory.getLogger(HighLevelDynamoDbLeaderboardRepository.class);

    /** Typed async table handle for {@link LeaderboardEntry}. */
    private final DynamoDbAsyncTable<LeaderboardEntry> leaderboardTable;

    /**
     * Creates the repository.
     *
     * @param enhancedClient the high-level enhanced async client
     * @param tableName      LeaderboardAggregate table name
     */
    public HighLevelDynamoDbLeaderboardRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${dynamodb.leaderboard-table-name}") String tableName) {
        this.leaderboardTable = enhancedClient.table(tableName, TableSchema.fromBean(LeaderboardEntry.class));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses the enhanced table {@code putItem} API for an idempotent overwrite.
     */
    @Override
    public CompletableFuture<Void> putLeaderboardEntry(LeaderboardEntry entry) {
        return leaderboardTable.putItem(entry)
                .thenRun(() -> logger.debug("Leaderboard entry stored [partitionKey={}, sortKey={}]",
                        entry.getPartitionKey(), entry.getSortKey()));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Builds a typed {@link Key} and deletes through the enhanced table API.
     */
    @Override
    public CompletableFuture<Void> deleteLeaderboardEntry(String partitionKey, String sortKey) {
        Key key = Key.builder()
                .partitionValue(partitionKey)
                .sortValue(sortKey)
                .build();

        return leaderboardTable.deleteItem(key)
                .thenRun(() -> logger.debug("Leaderboard entry deleted [partitionKey={}, sortKey={}]",
                        partitionKey, sortKey));
    }

    /**
     * {@inheritDoc}
     *
     * @implNote Uses the enhanced client's {@code query} with {@code scanIndexForward(false)} so
     *           the zero-padded score sort keys come back in descending order, giving the
     *           top-scoring entries first. Only the first page is consumed.
     */
    @Override
    public CompletableFuture<List<LeaderboardEntry>> queryTopN(String scope, int limit) {
        String partitionKey = LeaderboardEntry.buildPartitionKey(scope);

        QueryEnhancedRequest queryRequest = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(partitionKey).build()))
                .scanIndexForward(false)
                .limit(limit)
                .build();

        CompletableFuture<List<LeaderboardEntry>> result = new CompletableFuture<>();
        List<LeaderboardEntry> collected = new ArrayList<>();

        leaderboardTable.query(queryRequest).subscribe(new Subscriber<Page<LeaderboardEntry>>() {
            /** Upstream subscription handle used to request pages and cancel the stream. */
            private Subscription subscription;

            /** Stores the upstream subscription for request or cancel. */
            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                s.request(1);
            }

            /** Collects the single requested page and completes the result future. */
            @Override
            public void onNext(Page<LeaderboardEntry> page) {
                collected.addAll(page.items());
                subscription.cancel();
                result.complete(collected);
            }

            /** Propagates failures to the result future. */
            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            /** Completes with partial data if the publisher finishes without a page. */
            @Override
            public void onComplete() {
                if (!result.isDone()) {
                    result.complete(collected);
                }
            }
        });

        return result;
    }
}
