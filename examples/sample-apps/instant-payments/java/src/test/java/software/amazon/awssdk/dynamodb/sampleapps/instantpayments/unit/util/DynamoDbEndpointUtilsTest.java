package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.DynamoDbEndpointUtils;

/**
 * Unit tests for {@link DynamoDbEndpointUtils#isLocalEndpoint(String)} across local aliases,
 * AWS endpoints, and malformed URIs.
 */
@Tag("unit")
public class DynamoDbEndpointUtilsTest {

    @Test
    void isLocalEndpoint_localhost_shouldBeTrue() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://localhost:8000")).isTrue();
    }

    @Test
    void isLocalEndpoint_loopback_shouldBeTrue() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://127.0.0.1:8000")).isTrue();
    }

    @Test
    void isLocalEndpoint_dynamodbServiceName_shouldBeTrue() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://dynamodb:8000")).isTrue();
    }

    @Test
    void isLocalEndpoint_dynamodbLocalServiceName_shouldBeTrue() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://dynamodb-local:8000")).isTrue();
    }

    @Test
    void isLocalEndpoint_awsRegionalEndpoint_shouldBeFalse() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("https://dynamodb.eu-west-1.amazonaws.com")).isFalse();
    }

    @Test
    void isLocalEndpoint_nullHost_shouldBeFalse() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("not-a-uri")).isFalse();
    }

    @Test
    void isLocalEndpoint_malformed_shouldBeFalse() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint(":::invalid")).isFalse();
    }
}
