package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.config;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Runs {@link DynamoDbConfigIntegrationTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies the {@link DynamoDbAsyncClient} retry strategy in the full Spring context when
 * low-level repository beans are active. The enhanced client assertion is skipped because that
 * bean is not created in low-level mode.
 *
 * <p>Runs against DynamoDB Local via Testcontainers with a full Spring context.
 */
@Tag("integration")
class DynamoDbConfigLowLevelIntegrationTest extends DynamoDbConfigIntegrationTest {

    /**
     * Switches the Spring context to the low-level DynamoDB repository implementation.
     *
     * @param registry dynamic property registry for the test context
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
