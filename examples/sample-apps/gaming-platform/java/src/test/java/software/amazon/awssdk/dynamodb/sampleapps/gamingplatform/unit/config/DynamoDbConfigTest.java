package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
    void validPropertiesHighLevel_shouldCreateClients() {
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
    void validPropertiesLowLevel_shouldCreateAsyncClientOnly() {
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
    void blankEndpoint_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankRegion_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=  ",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void blankClientType_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=")
                .run(context -> assertThat(context).hasFailed());
    }
}
