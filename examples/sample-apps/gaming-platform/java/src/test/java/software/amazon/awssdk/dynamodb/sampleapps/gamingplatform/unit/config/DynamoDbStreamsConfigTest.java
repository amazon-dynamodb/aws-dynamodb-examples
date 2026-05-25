package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbStreamsConfig;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Unit tests for {@link DynamoDbStreamsConfig} bean wiring.
 */
@Tag("unit")
class DynamoDbStreamsConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbStreamsConfig.class);

    @Test
    void localEndpoint_shouldProvideStreamsAsyncClientBean() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1")
                .run(context -> assertThat(context).hasSingleBean(DynamoDbStreamsAsyncClient.class));
    }

    @Test
    void awsEndpoint_shouldProvideStreamsAsyncClientBean() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=https://dynamodb.eu-west-1.amazonaws.com",
                        "dynamodb.region=eu-west-1")
                .run(context -> assertThat(context).hasSingleBean(DynamoDbStreamsAsyncClient.class));
    }
}
