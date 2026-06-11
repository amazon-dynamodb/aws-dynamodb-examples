package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbStreamsConfig;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Unit tests for {@link DynamoDbStreamsConfig}, proving the streams client shares the same retry
 * strategy and call timeouts as the main {@link DynamoDbAsyncClient} by applying the single
 * {@link ClientOverrideConfiguration} bean built in {@link DynamoDbConfig}.
 *
 * <p>The resolved retry settings are read back through the public
 * {@code serviceClientConfiguration().overrideConfiguration()} accessor on each client, so the
 * assertions check what the SDK actually applied rather than what was passed to the builder.
 */
@Tag("unit")
public class DynamoDbStreamsConfigTest {

    private static final int SHARED_MAX_ATTEMPTS = 9;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class, DynamoDbStreamsConfig.class)
            .withPropertyValues(
                    "dynamodb.endpoint=http://localhost:8000",
                    "dynamodb.region=eu-west-1",
                    "dynamodb.client-type=high-level");

    @Test
    void dynamoDbStreamsAsyncClient_whenRegistered_shouldUseTunedRetryStrategyNotSdkDefaults() {
        contextRunner.run(context -> {
            DynamoDbStreamsAsyncClient streamsClient = context.getBean(DynamoDbStreamsAsyncClient.class);

            int resolvedMaxAttempts = retrieveMaxAttempts(streamsClient.serviceClientConfiguration().overrideConfiguration());
            int sdkDefaultMaxAttempts = retrieveSdkDefaultMaxAttempts();

            // The bug being fixed: without the shared override the streams client falls back to SDK
            // defaults. Prove the tuned value is applied and is genuinely different from the default.
            assertThat(sdkDefaultMaxAttempts)
                    .as("guard: the SDK default must differ from the tuned value for this test to be meaningful")
                    .isNotEqualTo(SHARED_MAX_ATTEMPTS);
            assertThat(resolvedMaxAttempts)
                    .as("streams client must use the shared tuned strategy (%d), not SDK defaults (%d)",
                            SHARED_MAX_ATTEMPTS, sdkDefaultMaxAttempts)
                    .isEqualTo(SHARED_MAX_ATTEMPTS);
        });
    }

    @Test
    void dynamoDbStreamsAsyncClient_whenRegistered_shouldMatchMainClientRetryAndCallTimeouts() {
        contextRunner.run(context -> {
            // A single shared override bean is injected into both the main client (DynamoDbConfig) and
            // the streams client (DynamoDbStreamsConfig), so both resolve the same retry and timeouts.
            assertThat(context).hasSingleBean(ClientOverrideConfiguration.class);

            ClientOverrideConfiguration mainClientOverride = context.getBean(DynamoDbAsyncClient.class)
                    .serviceClientConfiguration().overrideConfiguration();
            ClientOverrideConfiguration streamsClientOverride = context.getBean(DynamoDbStreamsAsyncClient.class)
                    .serviceClientConfiguration().overrideConfiguration();

            // Parity: the poller and the main client back off identically and time out identically.
            assertThat(retrieveMaxAttempts(streamsClientOverride)).isEqualTo(retrieveMaxAttempts(mainClientOverride));
            assertThat(streamsClientOverride.apiCallAttemptTimeout()).isEqualTo(mainClientOverride.apiCallAttemptTimeout());
            assertThat(streamsClientOverride.apiCallTimeout()).isEqualTo(mainClientOverride.apiCallTimeout());
        });
    }

    /** Reads the resolved max attempts from the client override retry strategy. */
    private static int retrieveMaxAttempts(ClientOverrideConfiguration override) {
        return override.retryStrategy()
                .orElseThrow(() -> new AssertionError("expected a resolved retry strategy on the client"))
                .maxAttempts();
    }

    /** Builds a streams client with no override to capture the SDK default {@code maxAttempts}. */
    private static int retrieveSdkDefaultMaxAttempts() {
        try (DynamoDbStreamsAsyncClient defaultClient = DynamoDbStreamsAsyncClient.builder()
                .region(Region.EU_WEST_1)
                .endpointOverride(URI.create("http://localhost:8000"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")))
                .build()) {
            return retrieveMaxAttempts(defaultClient.serviceClientConfiguration().overrideConfiguration());
        }
    }
}
