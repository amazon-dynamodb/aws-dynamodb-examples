package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository;

import java.util.List;
import java.util.Map;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Holds the result of a paginated query against the GameEvents table.
 *
 * @param events           events in the current page
 * @param lastEvaluatedKey the key to pass as {@code ExclusiveStartKey} for the next page,
 *                         or {@code null} when no more pages exist
 */
public record GameEventPage(List<GameEvent> events, Map<String, AttributeValue> lastEvaluatedKey) {
}
