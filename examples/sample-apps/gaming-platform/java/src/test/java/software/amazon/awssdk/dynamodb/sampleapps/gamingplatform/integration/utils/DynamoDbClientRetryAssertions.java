package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.retries.api.RetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Assertions for the explicit AWS SDK {@link RetryStrategy} wired on {@link DynamoDbAsyncClient}
 * in {@link DynamoDbConfig}
 * (nine total attempts including the first call, overriding the DynamoDB client default).
 */
public final class DynamoDbClientRetryAssertions {

    public static final int EXPECTED_SDK_MAX_ATTEMPTS = 9;

    /** Not instantiated. */
    private DynamoDbClientRetryAssertions() {
    }

    /**
     * Asserts the async client carries an explicit SDK retry strategy with the expected attempt budget.
     *
     * @param client application {@link DynamoDbAsyncClient} bean
     */
    public static void assertExplicitSdkRetryStrategy(DynamoDbAsyncClient client) {
        Optional<RetryStrategy> strategy = client.serviceClientConfiguration()
                .overrideConfiguration()
                .retryStrategy();
        assertThat(strategy).isPresent();
        assertThat(strategy.orElseThrow().maxAttempts()).isEqualTo(EXPECTED_SDK_MAX_ATTEMPTS);
    }

    /**
     * Asserts the enhanced client delegates to the same underlying async client retry configuration.
     *
     * @param enhancedClient enhanced client wrapping the module's {@link DynamoDbAsyncClient}
     */
    public static void assertExplicitSdkRetryStrategy(DynamoDbEnhancedAsyncClient enhancedClient) {
        assertExplicitSdkRetryStrategy(enhancedClient.dynamoDbAsyncClient());
    }
}
