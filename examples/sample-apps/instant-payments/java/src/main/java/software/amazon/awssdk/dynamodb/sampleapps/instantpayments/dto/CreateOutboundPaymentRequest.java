package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/v1/payments/outbound}.
 *
 * @param idempotencyKey client-chosen unique key for duplicate detection (typically a UUID)
 * @param merchantId merchant scope for the payment (used in merchant-level GSI queries)
 * @param debtorAccountId account to be debited
 * @param creditorIban IBAN of the payment receiver
 * @param creditorName name of the payment receiver
 * @param amount payment amount (must be positive)
 * @param currency ISO 4217 currency code (e.g. {@code USD}, {@code EUR})
 */
public record CreateOutboundPaymentRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String merchantId,
        @NotBlank String debtorAccountId,
        @NotBlank String creditorIban,
        @NotBlank String creditorName,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency) {
}
