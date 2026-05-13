package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.time.Instant;

/**
 * Response body for a successfully created outbound payment.
 *
 * @param paymentId server-generated unique payment identifier
 * @param state initial payment state (always {@code RECEIVED})
 * @param correlationId tracing identifier for end-to-end observability
 * @param createdAtUtc UTC instant of creation, ISO-8601 with {@code Z} in JSON
 */
public record CreateOutboundPaymentResponse(
        String paymentId,
        String state,
        String correlationId,
        Instant createdAtUtc) {
}
