package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.converter.ResponseSnapshotAttributeConverter;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link ResponseSnapshotAttributeConverter}, including response round-tripping and
 * failure on malformed DynamoDB attribute maps.
 */
@Tag("unit")
public class ResponseSnapshotAttributeConverterTest {

    private final ResponseSnapshotAttributeConverter converter = ResponseSnapshotAttributeConverter.create();

    @Test
    void transformFromAndTransformTo_shouldRoundTrip() {
        CreateOutboundPaymentResponse original = new CreateOutboundPaymentResponse(
                "pay_123", "RECEIVED", "corr_456", Instant.parse("2026-03-18T10:15:30Z"));

        AttributeValue attributeValue = converter.transformFrom(original);
        CreateOutboundPaymentResponse result = converter.transformTo(attributeValue);

        assertThat(result).isEqualTo(original);
        assertThat(result.paymentId()).isEqualTo("pay_123");
        assertThat(result.state()).isEqualTo("RECEIVED");
        assertThat(result.correlationId()).isEqualTo("corr_456");
        assertThat(result.createdAtUtc()).isEqualTo(Instant.parse("2026-03-18T10:15:30Z"));
    }

    @Test
    void transformTo_shouldParseMinimalMap() {
        AttributeValue attributeValue = AttributeValue.builder()
                .m(Map.of(
                        "paymentId", AttributeValue.builder().s("pay_min").build(),
                        "state", AttributeValue.builder().s("RECEIVED").build(),
                        "correlationId", AttributeValue.builder().s("corr_min").build(),
                        "createdAtUtc", AttributeValue.builder().s("1970-01-01T00:00:00Z").build()))
                .build();

        CreateOutboundPaymentResponse result = converter.transformTo(attributeValue);

        assertThat(result.paymentId()).isEqualTo("pay_min");
        assertThat(result.state()).isEqualTo("RECEIVED");
        assertThat(result.correlationId()).isEqualTo("corr_min");
        assertThat(result.createdAtUtc()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void transformTo_missingKeys_shouldThrow() {
        AttributeValue attributeValue = AttributeValue.builder()
                .m(Map.of("paymentId", AttributeValue.builder().s("only").build()))
                .build();

        assertThatThrownBy(() -> converter.transformTo(attributeValue))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(NullPointerException.class);
    }
}
