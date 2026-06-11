package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.math.BigDecimal;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;

/**
 * Builds a deterministic string from an outbound create request for idempotency hashing.
 *
 * <p>Field order and formatting are stable across JVM versions so the same logical request always
 * yields the same hash. Each field is length-prefixed as {@code length + ":" + value}, so the
 * concatenation is unambiguous, two different field tuples can never produce the same canonical
 * string. This removes the separator-injection risk of a plain delimiter, where a field value
 * containing the delimiter could otherwise reproduce a different request canonical form and collide
 * idempotency hashes.
 */
public final class IdempotencyCanonicalizer {

    /**
     * Colon that separates a field length from its value in the length-prefix encoding.
     */
    private static final char COLON_SEPARATOR = ':';

    /**
     * Not instantiable.
     */
    private IdempotencyCanonicalizer() {
    }

    /**
     * Canonical string for SHA-256 idempotency fingerprint (not JSON).
     *
     * <p>Each field is emitted as {@code length + ":" + value} in a fixed order. Because the length
     * prefix states exactly how many characters the value occupies, a field value cannot shift the
     * field boundaries or imitate another request even when it contains digits, colons, or control
     * characters. Equal logical requests still produce identical strings.
     *
     * <p>For example, fields {@code "ab"}, {@code "cd"} encode to {@code "2:ab2:cd"}, while
     * {@code "a"}, {@code "bcd"} encode to {@code "1:a3:bcd"}, so the two tuples never collide.
     *
     * @param request inbound create request
     * @return length-prefixed fields in fixed order
     *
     * @implNote {@link BigDecimal#stripTrailingZeros()} with {@link BigDecimal#toPlainString()}
     *     avoids hash drift between {@code 10.0} and {@code 10.00} and avoids scientific notation for the amount field.
     */
    public static String canonicalForm(CreateOutboundPaymentRequest request) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, request.idempotencyKey());
        appendField(sb, request.merchantId());
        appendField(sb, request.debtorAccountId());
        appendField(sb, request.creditorIban());
        appendField(sb, request.creditorName());
        appendField(sb, request.amount().stripTrailingZeros().toPlainString());
        appendField(sb, request.currency());
        return sb.toString();
    }

    /**
     * Appends one field as {@code value.length() + ":" + value} so the encoding stays unambiguous.
     *
     * @param sb    target builder
     * @param value field value (must not be null, request fields are {@code @NotBlank})
     */
    private static void appendField(StringBuilder sb, String value) {
        sb.append(value.length()).append(COLON_SEPARATOR).append(value);
    }
}
