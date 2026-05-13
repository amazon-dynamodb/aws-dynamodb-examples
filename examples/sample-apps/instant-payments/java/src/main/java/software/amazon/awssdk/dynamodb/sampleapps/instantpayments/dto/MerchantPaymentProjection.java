package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only projection of a payment for merchant-level list queries.
 *
 * <p>Backed by the {@code PaymentStreamHead} item indexed via
 * {@code GSI_MERCHANT_PAYMENTS} and {@code GSI_MERCHANT_STATE_PAYMENTS}.
 *
 * @param paymentId     payment identifier
 * @param state         current lifecycle state
 * @param version       optimistic concurrency / projection version (matches {@code lastSequence})
 * @param merchantId    merchant scope
 * @param correlationId tracing / end-to-end identifier
 * @param amount        monetary amount
 * @param currency      ISO currency code
 * @param createdAtUtc  creation time (UTC)
 * @param updatedAtUtc  last update time (UTC)
 * @param reasonCode    failure reason when state is terminal failure; otherwise often {@code null}
 */
public record MerchantPaymentProjection(
        String paymentId,
        String state,
        long version,
        String merchantId,
        String correlationId,
        BigDecimal amount,
        String currency,
        Instant createdAtUtc,
        Instant updatedAtUtc,
        String reasonCode) {
}
