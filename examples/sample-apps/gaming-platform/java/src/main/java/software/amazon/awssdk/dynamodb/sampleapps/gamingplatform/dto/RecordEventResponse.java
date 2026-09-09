package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

/**
 * Response body after recording a game event.
 *
 * @param eventId    generated event identifier
 * @param recordedAt ISO-8601 UTC timestamp
 */
public record RecordEventResponse(String eventId, String recordedAt) {
}
