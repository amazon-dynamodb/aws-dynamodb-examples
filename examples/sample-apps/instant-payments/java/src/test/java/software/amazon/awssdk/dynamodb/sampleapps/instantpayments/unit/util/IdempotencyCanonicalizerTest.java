package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.IdempotencyCanonicalizer;

/**
 * Unit tests for {@link IdempotencyCanonicalizer}: deterministic encoding, amount normalization,
 * and the length-prefix encoding that prevents separator-injection hash collisions.
 */
@Tag("unit")
public class IdempotencyCanonicalizerTest {

    /**
     * Builds a create request from the individual fields the canonical form encodes.
     *
     * @param idempotencyKey client supplied idempotency key
     * @param merchantId merchant identifier
     * @param debtorAccountId debtor account identifier
     * @param creditorIban creditor IBAN
     * @param creditorName creditor display name
     * @param amount payment amount
     * @param currency ISO currency code
     * @return request carrying the given field values
     */
    private static CreateOutboundPaymentRequest request(
            String idempotencyKey,
            String merchantId,
            String debtorAccountId,
            String creditorIban,
            String creditorName,
            BigDecimal amount,
            String currency) {
        return new CreateOutboundPaymentRequest(
                idempotencyKey, merchantId, debtorAccountId, creditorIban, creditorName, amount, currency);
    }

    @Test
    void canonicalForm_whenSameLogicalRequest_shouldProduceIdenticalString() {
        CreateOutboundPaymentRequest a = request(
                "idem-1", "merchantA", "account1", "IBAN1", "Alice", new BigDecimal("10.00"), "EUR");
        CreateOutboundPaymentRequest b = request(
                "idem-1", "merchantA", "account1", "IBAN1", "Alice", new BigDecimal("10.0"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(a))
                .isEqualTo(IdempotencyCanonicalizer.canonicalForm(b));
    }

    @Test
    void canonicalForm_whenAmountHasTrailingZeros_shouldNormalizeToPlainString() {
        CreateOutboundPaymentRequest request = request(
                "idem-1", "merchantA", "account1", "IBAN1", "Alice", new BigDecimal("10.50"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(request)).contains("4:10.5");
    }

    @Test
    void canonicalForm_whenLegacyUnitSeparatorInjectedInCreditorName_shouldNotCollideWithDifferentRequest() {
        // Crafted request: creditorName embeds the old U+001F separator plus other field values.
        CreateOutboundPaymentRequest crafted = request(
                "idem-1",
                "merchantA",
                "account1",
                "IBAN1",
                "Alice\u001FmerchantA\u001Faccount1\u001FIBAN1\u001FBob",
                new BigDecimal("10.00"),
                "EUR");

        // The request the attacker is trying to imitate.
        CreateOutboundPaymentRequest real = request(
                "idem-1", "merchantA", "account1", "IBAN1", "Bob", new BigDecimal("10.00"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(crafted))
                .isNotEqualTo(IdempotencyCanonicalizer.canonicalForm(real));
    }

    @Test
    void canonicalForm_whenFieldBoundaryShifted_shouldStayDistinct() {
        // Moving a character across the merchantId/debtorAccountId boundary must change the encoding.
        CreateOutboundPaymentRequest left = request(
                "idem-1", "ab", "cd", "IBAN1", "Alice", new BigDecimal("1"), "EUR");
        CreateOutboundPaymentRequest right = request(
                "idem-1", "a", "bcd", "IBAN1", "Alice", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(left))
                .isNotEqualTo(IdempotencyCanonicalizer.canonicalForm(right));
    }

    @Test
    void canonicalForm_whenFieldContainsColonSeparator_shouldEncodeColonLiterallyInsideValue() {
        // The colon is the encoding separator. A field value containing a colon must be encoded
        // literally as part of the value (counted by the length prefix), not treated as structure.
        CreateOutboundPaymentRequest request = request(
                "idem-1", "m", "d", "i", "2:ab", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(request))
                .isEqualTo("6:idem-11:m1:d1:i4:2:ab1:13:EUR");
    }

    @Test
    void canonicalForm_whenColonInjectedToFakeFieldBoundary_shouldNotCollide() {
        // Attacker tries to imitate field boundaries using the colon separator itself: a creditorIban
        // value carrying a colon ("1:2") versus a creditorName value carrying it ("2:34"). A naive
        // split-on-colon scheme could be confused, length prefixing keeps the two requests distinct.
        CreateOutboundPaymentRequest crafted = request(
                "idem-1", "merchantA", "account1", "1:2", "34", new BigDecimal("1"), "EUR");
        CreateOutboundPaymentRequest real = request(
                "idem-1", "merchantA", "account1", "1", "2:34", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(crafted))
                .isNotEqualTo(IdempotencyCanonicalizer.canonicalForm(real));
    }

    @Test
    void canonicalForm_whenFieldContainsControlCharacter_shouldEncodeItLiterallyInsideValue() {
        // A control character (here the old U+001F separator) inside a field is counted by the length
        // prefix and emitted literally, it is data, not structure.
        CreateOutboundPaymentRequest request = request(
                "idem-1", "m", "d", "i", "a\u001Fb", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(request))
                .isEqualTo("6:idem-11:m1:d1:i3:a\u001Fb1:13:EUR");
    }

    @Test
    void canonicalForm_whenControlCharacterShiftedAcrossBoundary_shouldNotCollide() {
        // Moving a control character across the creditorIban/creditorName boundary must stay distinct.
        CreateOutboundPaymentRequest crafted = request(
                "idem-1", "merchantA", "account1", "x\u001F", "y", new BigDecimal("1"), "EUR");
        CreateOutboundPaymentRequest real = request(
                "idem-1", "merchantA", "account1", "x", "\u001Fy", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(crafted))
                .isNotEqualTo(IdempotencyCanonicalizer.canonicalForm(real));
    }

    @Test
    void canonicalForm_whenFieldsDiffer_shouldUseLengthPrefixedEncoding() {
        CreateOutboundPaymentRequest request = request(
                "idem-1", "merchantA", "account1", "IBAN1", "Alice", new BigDecimal("1"), "EUR");

        assertThat(IdempotencyCanonicalizer.canonicalForm(request))
                .isEqualTo("6:idem-19:merchantA8:account15:IBAN15:Alice1:13:EUR");
    }
}
