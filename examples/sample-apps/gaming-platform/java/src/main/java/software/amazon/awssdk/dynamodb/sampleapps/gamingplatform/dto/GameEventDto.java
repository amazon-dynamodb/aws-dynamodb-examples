package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.util.Map;

/**
 * Single game event in a paginated event history response.
 *
 * @param eventId         event identifier
 * @param eventType       event type (e.g. PVP_MATCH, PURCHASE)
 * @param recordedAt      ISO-8601 UTC timestamp
 * @param eventAttributes event-specific fields
 */
public record GameEventDto(String eventId,
                           String eventType,
                           String recordedAt,
                           Map<String, Object> eventAttributes) {
}
