package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils.DynamoDbClientRetryAssertions;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Unit tests for {@link DynamoDbConfig} bean wiring, validation, and explicit DynamoDB SDK retry strategy.
 */
@Tag("unit")
class DynamoDbConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class);

    @Test
    void contextRunner_whenHighLevelPropertiesValid_shouldCreateClients() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                    assertThat(context).hasSingleBean(DynamoDbEnhancedAsyncClient.class);
                    DynamoDbAsyncClient client = context.getBean(DynamoDbAsyncClient.class);
                    DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(client);
                    DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(
                            context.getBean(DynamoDbEnhancedAsyncClient.class));
                });
    }

    @Test
    void contextRunner_whenLowLevelPropertiesValid_shouldCreateAsyncClientOnly() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=low-level")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                    assertThat(context).doesNotHaveBean(DynamoDbEnhancedAsyncClient.class);
                    DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(
                            context.getBean(DynamoDbAsyncClient.class));
                });
    }

    @Test
    void contextRunner_whenEndpointBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextRunner_whenRegionBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=  ",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextRunner_whenClientTypeBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextRunner_whenTtlBelowMinimum_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.game-events-ttl-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextRunner_whenTtlAboveMaximum_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.game-events-ttl-seconds=40000000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void contextRunner_whenTtlAtMinimumBoundary_shouldStartContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.game-events-ttl-seconds=60")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void contextRunner_whenTtlAtMaximumBoundary_shouldStartContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.game-events-ttl-seconds=31536000")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void dynamoDbAsyncClient_whenBuilt_shouldApplyCallTimeouts() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    DynamoDbAsyncClient client = context.getBean(DynamoDbAsyncClient.class);
                    ClientOverrideConfiguration override =
                            client.serviceClientConfiguration().overrideConfiguration();

                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofMillis(1500));
                    // End-to-end budget must not be smaller than one attempt budget.
                    assertThat(override.apiCallTimeout().orElseThrow())
                            .isGreaterThanOrEqualTo(override.apiCallAttemptTimeout().orElseThrow());
                });
    }

    @Test
    void nettyHttpClientTuning_whenConfigured_shouldRaiseSdkDefaults() {
        int maxConcurrency = (Integer) ReflectionTestUtils.getField(
                DynamoDbConfig.class, "HTTP_MAX_CONCURRENCY");
        Duration acquisitionTimeout = (Duration) ReflectionTestUtils.getField(
                DynamoDbConfig.class, "HTTP_CONNECTION_ACQUISITION_TIMEOUT");

        assertThat(maxConcurrency).isEqualTo(200);
        assertThat(acquisitionTimeout).isEqualTo(Duration.ofSeconds(2));
    }
}
