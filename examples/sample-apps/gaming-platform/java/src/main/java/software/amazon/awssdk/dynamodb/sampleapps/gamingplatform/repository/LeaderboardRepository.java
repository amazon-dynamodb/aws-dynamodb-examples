package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;

/**
 * Data access contract for the Leaderboard DynamoDB table.
 *
 * <p>Two implementations are provided: one using the low-level {@code DynamoDbAsyncClient}
 * and one using the high-level {@code DynamoDbEnhancedAsyncClient}. The active implementation
 * is selected at startup by {@code dynamodb.client-type}.
 */
public interface LeaderboardRepository {

    /**
     * Writes or overwrites a leaderboard entry (idempotent {@code PutItem}).
     *
     * @param entry entry to persist
     */
    CompletableFuture<Void> putLeaderboardEntry(LeaderboardEntry entry);

    /**
     * Deletes a leaderboard entry by its exact key (used before re-inserting with an updated score).
     *
     * @param partitionKey partition key of the entry
     * @param sortKey sort key of the entry
     */
    CompletableFuture<Void> deleteLeaderboardEntry(String partitionKey, String sortKey);

    /**
     * Queries the top-N entries for a leaderboard scope, sorted by score descending.
     *
     * @param scope leaderboard scope, for example {@code GLOBAL} or {@code PLATFORM#PS5}
     * @param limit maximum entries to return
     * @return top entries for the scope, highest score first
     */
    CompletableFuture<List<LeaderboardEntry>> queryTopN(String scope, int limit);
}
