package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.service;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs {@link DynamoDbStreamsLeaderboardListenerIntegrationTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies that PVP_MATCH events written through the low-level repository path still reach
 * the leaderboard projection listener.
 *
 * <p>Runs against DynamoDB Local via Testcontainers with a full Spring context.
 */
@Tag("integration")
@Tag("smoke")
class DynamoDbStreamsLeaderboardListenerLowLevelIntegrationTest
        extends DynamoDbStreamsLeaderboardListenerIntegrationTest {

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
