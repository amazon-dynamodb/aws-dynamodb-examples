package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read model for an outbound payment: scalar fields from {@linkplain
 * software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer replay}
 * of the event stream plus ordered history.
 *
 * @param paymentId        server-issued id
 * @param state            current lifecycle state (folded from events)
 * @param correlationId    tracing identifier
 * @param createdAtUtc     creation instant in UTC (from first event), ISO-8601 with {@code Z} in JSON
 * @param updatedAtUtc     last applied event instant in UTC, ISO-8601 with {@code Z} in JSON
 * @param debtorAccountId  debited account
 * @param creditorIban     payee IBAN
 * @param creditorName     payee name
 * @param amount           payment amount
 * @param currency         ISO currency code
 * @param idempotencyKey   client idempotency key from create
 * @param reasonCode       rejection reason when in {@code REJECTED}, else {@code null}
 * @param version          stream revision (matches {@code PAYMENT_STREAM_HEAD.lastSequence})
 * @param events           domain events sorted by sequence / sort key
 */
public record GetOutboundPaymentResponse(
        String paymentId,
        String state,
        String correlationId,
        Instant createdAtUtc,
        Instant updatedAtUtc,
        String debtorAccountId,
        String creditorIban,
        String creditorName,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String reasonCode,
        int version,
        List<PaymentEventResponse> events) {
}
