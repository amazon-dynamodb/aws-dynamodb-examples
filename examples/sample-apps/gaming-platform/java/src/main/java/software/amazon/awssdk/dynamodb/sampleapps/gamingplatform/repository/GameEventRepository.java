package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Data access contract for the GameEvent DynamoDB table.
 *
 * <p>Two implementations are provided: one using the low-level {@code DynamoDbAsyncClient}
 * and one using the high-level {@code DynamoDbEnhancedAsyncClient}. The active implementation
 * is selected at startup by {@code dynamodb.client-type}.
 */
public interface GameEventRepository {

    /**
     * Appends a game event to the table (unconditional {@code PutItem}).
     *
     * @param event event to persist
     */
    CompletableFuture<Void> appendEvent(GameEvent event);

    /**
     * Queries events for a player with pagination support.
     *
     * @param playerId           the player whose events to query
     * @param limit              maximum number of events per page
     * @param scanIndexForward   DynamoDB Query {@code ScanIndexForward}: {@code true} for ascending sort-key
     *                           order (oldest first here), {@code false} for descending (newest first)
     * @param exclusiveStartKey  pagination token from a previous call, or {@code null} for the first page
     * @return a page of events with the last evaluated key for token encoding
     */
    CompletableFuture<GameEventPage> queryEventsByPlayer(String playerId, int limit, boolean scanIndexForward,
                                                          Map<String, AttributeValue> exclusiveStartKey);
}
