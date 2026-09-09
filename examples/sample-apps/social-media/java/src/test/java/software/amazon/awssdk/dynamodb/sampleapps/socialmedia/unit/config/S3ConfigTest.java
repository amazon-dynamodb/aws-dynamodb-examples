package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.S3Config;

/**
 * Unit coverage for S3 client call-timeout wiring.
 *
 * <p>Confirms the async S3 override uses the named DynamoDB attempt and call timeouts. No object
 * store is required.
 */
@Tag("unit")
class S3ConfigTest {

    @Test
    void s3ClientOverrideConfiguration_whenBuilt_usesDynamoDbCallTimeouts() {
        S3Config config = new S3Config();

        ClientOverrideConfiguration override = ReflectionTestUtils.invokeMethod(
                config, "s3ClientOverrideConfiguration");

        assertThat(override).isNotNull();
        assertThat(override.apiCallAttemptTimeout()).contains(DynamoDbConfig.API_CALL_ATTEMPT_TIMEOUT);
        assertThat(override.apiCallTimeout()).contains(DynamoDbConfig.API_CALL_TIMEOUT);
    }
}
