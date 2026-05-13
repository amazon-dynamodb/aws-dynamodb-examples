package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * API representation of a funds reservation held against an account.
 *
 * @param reservationId unique reservation identifier
 * @param paymentId     the payment this reservation was created for
 * @param amount        reserved amount
 * @param status        lifecycle status ({@code ACTIVE}, {@code CONSUMED}, or {@code RELEASED})
 * @param createdAtUtc UTC instant when the reservation was created, ISO-8601 with {@code Z} in JSON
 */
public record ReservationResponse(
        String reservationId,
        String paymentId,
        BigDecimal amount,
        String status,
        Instant createdAtUtc) {
}
