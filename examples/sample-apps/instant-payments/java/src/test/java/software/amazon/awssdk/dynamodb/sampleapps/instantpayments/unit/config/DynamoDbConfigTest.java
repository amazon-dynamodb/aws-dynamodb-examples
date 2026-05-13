package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbConfig;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Unit tests for {@link DynamoDbConfig} property validation and client bean registration.
 */
@Tag("unit")
public class DynamoDbConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class);

    @Test
    void validProperties_shouldCreateClients() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                    assertThat(context).hasSingleBean(DynamoDbEnhancedAsyncClient.class);
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
