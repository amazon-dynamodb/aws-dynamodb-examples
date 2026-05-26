package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.DynamoDbEndpointUtils;

/**
 * Unit tests for {@link DynamoDbEndpointUtils}.
 *
 * <p>Verifies local-endpoint detection used to tune client behaviour for DynamoDB Local.
 */
@Tag("unit")
class DynamoDbEndpointUtilsTest {

    @Test
    void isLocalEndpoint_whenHostIsLocalhost_shouldReturnTrue() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://localhost:8000")).isTrue();
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("http://127.0.0.1:8000")).isTrue();
    }

    @Test
    void isLocalEndpoint_whenHostIsAwsRegionalEndpoint_shouldReturnFalse() {
        assertThat(DynamoDbEndpointUtils.isLocalEndpoint("https://dynamodb.eu-west-1.amazonaws.com")).isFalse();
    }
}
