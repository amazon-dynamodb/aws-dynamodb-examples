package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.time.Instant;

/**
 * Standard error response body.
 *
 * @param error machine-readable error code (e.g. {@code IDEMPOTENCY_CONFLICT})
 * @param message human-readable description
 * @param timestamp time the error occurred
 */
public record ErrorResponse(
        String error,
        String message,
        Instant timestamp) {
}
