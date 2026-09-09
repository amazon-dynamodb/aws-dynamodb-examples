package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.PaginationTokenCodec;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit coverage for page-depth encoding and path-user string-attribute checks in
 * {@link PaginationTokenCodec}.
 *
 * <p>Exercises the envelope that stores depth alongside a {@code LastEvaluatedKey} map. No Docker
 * or Spring context is required.
 */
@Tag("unit")
class PaginationTokenCodecTest {

    private static final Map<String, AttributeValue> KEY = Map.of(
            "timelinePostId", AttributeValue.fromS("post_1"),
            "timelineCreatedAt", AttributeValue.fromS("2026-01-01T00:00:00Z"));

    @Test
    void encode_whenKeyIsEmpty_returnsNull() {
        assertThat(PaginationTokenCodec.encode(Map.of())).isNull();
        assertThat(PaginationTokenCodec.encode(Map.of(), 4)).isNull();
    }

    @Test
    void encode_whenFirstPage_storesDepthOne() {
        String token = PaginationTokenCodec.encode(KEY);

        assertThat(PaginationTokenCodec.depth(token)).isEqualTo(1);
        assertThat(PaginationTokenCodec.decode(token)).isEqualTo(KEY);
    }

    @Test
    void encode_whenPreviousDepthIsOne_storesDepthTwo() {
        String token = PaginationTokenCodec.encode(KEY, 1);

        assertThat(PaginationTokenCodec.depth(token)).isEqualTo(2);
        assertThat(PaginationTokenCodec.decode(token)).isEqualTo(KEY);
    }

    @Test
    void encode_whenPreviousDepthIsNinetyNine_storesDepthOneHundred() {
        String token = PaginationTokenCodec.encode(KEY, 99);

        assertThat(PaginationTokenCodec.depth(token)).isEqualTo(100);
        assertThat(PaginationTokenCodec.decode(token)).containsOnlyKeys("timelinePostId", "timelineCreatedAt");
    }

    @Test
    void depth_whenTokenIsBlank_returnsZero() {
        assertThat(PaginationTokenCodec.depth(null)).isEqualTo(0);
        assertThat(PaginationTokenCodec.depth("")).isEqualTo(0);
        assertThat(PaginationTokenCodec.depth("   ")).isEqualTo(0);
    }

    @Test
    void decode_whenTokenIsBlank_returnsNull() {
        assertThat(PaginationTokenCodec.decode(null)).isNull();
        assertThat(PaginationTokenCodec.decode("")).isNull();
    }

    @Test
    void decode_whenTokenHasZeroDepth_rejectsToken() {
        String token = envelopeToken("{\"depth\":0,\"key\":{\"timelinePostId\":{\"type\":\"S\",\"value\":\"post_1\"}}}");

        assertThatThrownBy(() -> PaginationTokenCodec.decode(token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        assertThatThrownBy(() -> PaginationTokenCodec.depth(token))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void decode_whenTokenOmitsDepth_rejectsToken() {
        String token = envelopeToken("{\"key\":{\"timelinePostId\":{\"type\":\"S\",\"value\":\"post_1\"}}}");

        assertThatThrownBy(() -> PaginationTokenCodec.decode(token))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void requireMatchingStringAttribute_whenValueEqualsExpected_doesNotThrow() {
        Map<String, AttributeValue> decoded = Map.of("timelineUserId", AttributeValue.fromS("user_1"));

        PaginationTokenCodec.requireMatchingStringAttribute(decoded, "timelineUserId", "user_1", "token");
    }

    @Test
    void requireMatchingStringAttribute_whenAttributeIsMissing_rejectsToken() {
        Map<String, AttributeValue> decoded = Map.of("timelinePostId", AttributeValue.fromS("post_1"));

        assertThatThrownBy(() -> PaginationTokenCodec.requireMatchingStringAttribute(
                decoded, "timelineUserId", "user_1", "token"))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    @Test
    void requireMatchingStringAttribute_whenValueDoesNotMatch_rejectsToken() {
        Map<String, AttributeValue> decoded = Map.of("timelineUserId", AttributeValue.fromS("user_other"));

        assertThatThrownBy(() -> PaginationTokenCodec.requireMatchingStringAttribute(
                decoded, "timelineUserId", "user_1", "token"))
                .isInstanceOf(InvalidPaginationTokenException.class);
    }

    /**
     * Encodes a raw JSON envelope as a URL-safe Base64 token.
     *
     * @param json envelope JSON
     * @return opaque token string
     */
    private static String envelopeToken(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
