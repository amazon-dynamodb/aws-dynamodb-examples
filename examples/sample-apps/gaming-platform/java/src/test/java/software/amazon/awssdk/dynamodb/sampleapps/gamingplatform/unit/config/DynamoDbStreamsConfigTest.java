package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbStreamsConfig;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Unit tests for {@link DynamoDbStreamsConfig} bean wiring and the shared client override.
 */
@Tag("unit")
class DynamoDbStreamsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ClientOverrideConfiguration.class,
                    () -> new DynamoDbConfig().dynamoDbClientOverrideConfiguration())
            .withUserConfiguration(DynamoDbStreamsConfig.class);

    @Test
    void contextRunner_whenLocalEndpointConfigured_shouldProvideStreamsAsyncClientBean() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1")
                .run(context -> assertThat(context).hasSingleBean(DynamoDbStreamsAsyncClient.class));
    }

    @Test
    void contextRunner_whenAwsEndpointConfigured_shouldProvideStreamsAsyncClientBean() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=https://dynamodb.eu-west-1.amazonaws.com",
                        "dynamodb.region=eu-west-1")
                .run(context -> assertThat(context).hasSingleBean(DynamoDbStreamsAsyncClient.class));
    }

    @Test
    void contextRunner_whenLocalEndpointConfigured_shouldShareMainClientRetryAndTimeouts() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1")
                .run(context -> {
                    DynamoDbStreamsAsyncClient client = context.getBean(DynamoDbStreamsAsyncClient.class);
                    ClientOverrideConfiguration override =
                            client.serviceClientConfiguration().overrideConfiguration();

                    assertThat(override.retryStrategy().orElseThrow().maxAttempts()).isEqualTo(9);
                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofMillis(1500));
                });
    }
}
