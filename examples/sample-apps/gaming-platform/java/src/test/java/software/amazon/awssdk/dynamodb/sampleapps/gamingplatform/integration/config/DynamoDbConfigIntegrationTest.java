package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils.DynamoDbClientRetryAssertions;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Integration tests for {@link DynamoDbConfig}.
 *
 * <p>Verifies that the full Spring context wires a {@link DynamoDbAsyncClient} whose SDK-level
 * retry policy matches the explicit configuration in {@link DynamoDbConfig}.
 */
@Tag("integration")
class DynamoDbConfigIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Autowired(required = false)
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Test
    void dynamoDbBeansShouldExposeExplicitSdkRetryStrategy() {
        DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbAsyncClient);
        if (dynamoDbEnhancedAsyncClient != null) {
            DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbEnhancedAsyncClient);
        }
    }
}
