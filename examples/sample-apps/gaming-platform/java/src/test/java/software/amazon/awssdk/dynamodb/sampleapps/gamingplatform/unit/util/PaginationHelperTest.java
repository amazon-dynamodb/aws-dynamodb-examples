package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.PaginationHelper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PaginationHelper}.
 *
 * <p>Verifies base64 JSON encoding and decoding of DynamoDB pagination tokens plus invalid input
 * handling.
 */
@Tag("unit")
class PaginationHelperTest {

    @Test
    void encodeDecode_whenValidKey_shouldRoundTrip() {
        Map<String, AttributeValue> original = new LinkedHashMap<>();
        original.put("PK", AttributeValue.fromS("USER#123"));
        original.put("SK", AttributeValue.fromS("EVT#2026-04-03T12:10:00Z#e5"));

        String token = PaginationHelper.encodePaginationToken(original);
        assertThat(token).isNotNull().isNotBlank();

        Map<String, AttributeValue> decoded = PaginationHelper.decodePaginationToken(token);
        assertThat(decoded).isNotNull();
        assertThat(decoded.get("PK").s()).isEqualTo("USER#123");
        assertThat(decoded.get("SK").s()).isEqualTo("EVT#2026-04-03T12:10:00Z#e5");
    }

    @Test
    void decodeExclusiveStartKey_whenInputNull_shouldReturnNull() {
        assertThat(PaginationHelper.encodePaginationToken(null)).isNull();
    }

    @Test
    void decodeExclusiveStartKey_whenInputEmpty_shouldReturnNull() {
        assertThat(PaginationHelper.encodePaginationToken(Map.of())).isNull();
    }

    @Test
    void decodeExclusiveStartKey_whenTokenNull_shouldReturnNull() {
        assertThat(PaginationHelper.decodePaginationToken(null)).isNull();
    }

    @Test
    void decodeExclusiveStartKey_whenTokenBlank_shouldReturnNull() {
        assertThat(PaginationHelper.decodePaginationToken("   ")).isNull();
    }

    @Test
    void decodeExclusiveStartKey_whenBase64Invalid_shouldThrow() {
        assertThatThrownBy(() -> PaginationHelper.decodePaginationToken("not-valid!!!"))
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token: not-valid!!!");
    }

    @Test
    void decodeExclusiveStartKey_whenJsonTampered_shouldThrow() {
        String tampered = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> PaginationHelper.decodePaginationToken(tampered))
                .isInstanceOf(InvalidPaginationTokenException.class)
                .hasMessage("Invalid pagination token: " + tampered);
    }
}
