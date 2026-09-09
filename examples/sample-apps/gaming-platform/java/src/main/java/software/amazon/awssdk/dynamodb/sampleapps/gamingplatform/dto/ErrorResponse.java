package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.time.Instant;

/**
 * Standard error response body for all API error conditions.
 *
 * @param error     machine-readable error code (e.g. {@code PLAYER_NOT_FOUND})
 * @param message   human-readable detail message
 * @param timestamp time the error was produced
 */
public record ErrorResponse(String error, String message, Instant timestamp) {
}
