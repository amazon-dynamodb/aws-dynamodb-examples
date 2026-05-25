package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbPlayerStateRepository;

/**
 * Runs {@link ProgressionIntegrationTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies the low-level {@link LowLevelDynamoDbPlayerStateRepository} path for XP updates,
 * optimistic locking, and level-up currency rewards end to end.
 *
 * <p>Runs against DynamoDB Local via Testcontainers with a full Spring context.
 */
@Tag("integration")
class ProgressionLowLevelIntegrationTest extends ProgressionIntegrationTest {

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
