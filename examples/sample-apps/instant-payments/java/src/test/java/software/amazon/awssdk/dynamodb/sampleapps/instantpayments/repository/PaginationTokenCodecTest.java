package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link PaginationTokenCodec}.
 *
 * <p>The codec is package-private, so this test lives in the same package to exercise the encode and
 * decode round-trip across every supported attribute type and the three rejection paths that turn
 * token misuse into a clean HTTP 400 instead of a leaked DynamoDB error. The merchant-list integration
 * tests only ever carry string keys, so the numeric, binary, boolean, null, and set branches are
 * covered here rather than end to end.
 */
@Tag("unit")
class PaginationTokenCodecTest {

    @Test
    void encode_whenKeyMapNullOrEmpty_shouldReturnNullForNoNextPage() {
        assertThat(PaginationTokenCodec.encode(null)).isNull();
        assertThat(PaginationTokenCodec.encode(Map.of())).isNull();
    }

    @Test
    void decode_whenTokenNullOrBlank_shouldReturnNullForFirstPage() {
        assertThat(PaginationTokenCodec.decode(null)).isNull();
        assertThat(PaginationTokenCodec.decode("   ")).isNull();
    }

    @Test
    void encodeThenDecode_whenStringAndNumberKeys_shouldRoundTripValues() {
        Map<String, AttributeValue> key = Map.of(
                "merchantId", AttributeValue.builder().s("merch_1").build(),
                "createdAtUtc", AttributeValue.builder().n("42").build());

        Map<String, AttributeValue> decoded = PaginationTokenCodec.decode(PaginationTokenCodec.encode(key));

        assertThat(decoded.get("merchantId").s()).isEqualTo("merch_1");
        assertThat(decoded.get("createdAtUtc").n()).isEqualTo("42");
    }

    @Test
    void encodeThenDecode_whenBooleanAndNullKeys_shouldRoundTripValues() {
        Map<String, AttributeValue> key = Map.of(
                "flag", AttributeValue.builder().bool(true).build(),
                "missing", AttributeValue.builder().nul(true).build());

        Map<String, AttributeValue> decoded = PaginationTokenCodec.decode(PaginationTokenCodec.encode(key));

        assertThat(decoded.get("flag").bool()).isTrue();
        assertThat(decoded.get("missing").nul()).isTrue();
    }

    @Test
    void encodeThenDecode_whenBinaryKey_shouldRoundTripBytes() {
        SdkBytes bytes = SdkBytes.fromUtf8String("payload");
        Map<String, AttributeValue> key = Map.of("blob", AttributeValue.builder().b(bytes).build());

        Map<String, AttributeValue> decoded = PaginationTokenCodec.decode(PaginationTokenCodec.encode(key));

        assertThat(decoded.get("blob").b()).isEqualTo(bytes);
    }

    @Test
    void encodeThenDecode_whenSetKeys_shouldRoundTripStringNumberAndBinarySets() {
        SdkBytes member = SdkBytes.fromUtf8String("bs");
        Map<String, AttributeValue> key = Map.of(
                "stringSet", AttributeValue.builder().ss("a", "b").build(),
                "numberSet", AttributeValue.builder().ns("1", "2").build(),
                "binarySet", AttributeValue.builder().bs(member).build());

        Map<String, AttributeValue> decoded = PaginationTokenCodec.decode(PaginationTokenCodec.encode(key));

        assertThat(decoded.get("stringSet").ss()).containsExactly("a", "b");
        assertThat(decoded.get("numberSet").ns()).containsExactly("1", "2");
        assertThat(decoded.get("binarySet").bs()).containsExactly(member);
    }

    @Test
    void decode_whenNotBase64_shouldThrowInvalidPaginationToken() {
        assertThatThrownBy(() -> PaginationTokenCodec.decode("not-base64-!!"))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void decode_whenEmptyJsonObject_shouldThrowInvalidPaginationToken() {
        String emptyObjectToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes());

        assertThatThrownBy(() -> PaginationTokenCodec.decode(emptyObjectToken))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void requireKeyAttribute_whenDiscriminatorPresent_shouldNotThrow() {
        Map<String, AttributeValue> decoded = Map.of(
                PaginationTokenCodec.GSI_MERCHANT_PAYMENTS_DISCRIMINATOR,
                AttributeValue.builder().s("pay_1").build());

        PaginationTokenCodec.requireKeyAttribute(
                decoded, PaginationTokenCodec.GSI_MERCHANT_PAYMENTS_DISCRIMINATOR, "token");
    }

    @Test
    void requireKeyAttribute_whenDiscriminatorMissing_shouldThrowForCrossEndpointReuse() {
        Map<String, AttributeValue> decoded = Map.of(
                PaginationTokenCodec.GSI_MERCHANT_STATE_PAYMENTS_DISCRIMINATOR,
                AttributeValue.builder().s("COMPLETED").build());

        assertThatThrownBy(() -> PaginationTokenCodec.requireKeyAttribute(
                decoded, PaginationTokenCodec.GSI_MERCHANT_PAYMENTS_DISCRIMINATOR, "token"))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void requireMatchingMerchantId_whenMerchantMatches_shouldNotThrow() {
        Map<String, AttributeValue> decoded = Map.of(
                PaginationTokenCodec.MERCHANT_ID_KEY, AttributeValue.builder().s("merch_1").build());

        PaginationTokenCodec.requireMatchingMerchantId(decoded, "merch_1", "token");
    }

    @Test
    void requireMatchingMerchantId_whenMerchantDiffersOrAbsent_shouldThrowForCrossMerchantReuse() {
        Map<String, AttributeValue> otherMerchant = Map.of(
                PaginationTokenCodec.MERCHANT_ID_KEY, AttributeValue.builder().s("merch_2").build());
        Map<String, AttributeValue> noMerchant = Map.of(
                "paymentId", AttributeValue.builder().s("pay_1").build());

        assertThatThrownBy(() -> PaginationTokenCodec.requireMatchingMerchantId(otherMerchant, "merch_1", "token"))
                .isInstanceOf(InvalidPaginationTokenException.class);
        assertThatThrownBy(() -> PaginationTokenCodec.requireMatchingMerchantId(noMerchant, "merch_1", "token"))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }
}
