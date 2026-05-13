package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;

/**
 * Builds a deterministic string from an outbound create request for idempotency hashing.
 *
 * <p>Field order and formatting are stable across JVM versions so the same logical request always
 * yields the same hash. Uses ASCII unit separator {@code U+001F} between fields (unlikely in IBAN,
 * names, or currency codes).
 */
public final class IdempotencyCanonicalizer {

    /**
     * ASCII unit separator ({@code U+001F}) between canonicalised fields; not expected in IBANs, names, or currency codes.
     */
    private static final String SEP = "\u001F";

    /**
     * Not instantiable.
     */
    private IdempotencyCanonicalizer() {
    }

    /**
     * Canonical string for SHA-256 idempotency fingerprint (not JSON).
     *
     * @param request inbound create request
     * @return joined fields in fixed order
     *
     * @implNote {@link java.math.BigDecimal#stripTrailingZeros()} with {@link java.math.BigDecimal#toPlainString()}
     *     avoids hash drift between {@code 10.0} and {@code 10.00} and avoids scientific notation for the amount field.
     */
    public static String canonicalForm(CreateOutboundPaymentRequest request) {
        return request.idempotencyKey()
                + SEP + request.merchantId()
                + SEP + request.debtorAccountId()
                + SEP + request.creditorIban()
                + SEP + request.creditorName()
                + SEP + request.amount().stripTrailingZeros().toPlainString()
                + SEP + request.currency();
    }
}
