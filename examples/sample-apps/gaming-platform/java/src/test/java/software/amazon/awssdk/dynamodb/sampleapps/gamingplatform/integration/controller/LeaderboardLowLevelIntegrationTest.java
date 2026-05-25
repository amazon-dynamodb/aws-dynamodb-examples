package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbLeaderboardRepository;

/**
 * Runs {@link LeaderboardIntegrationTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies the low-level {@link LowLevelDynamoDbLeaderboardRepository} path for scoped queries,
 * score ordering, and limit handling end to end.
 *
 * <p>Runs against DynamoDB Local via Testcontainers with a full Spring context.
 */
@Tag("integration")
class LeaderboardLowLevelIntegrationTest extends LeaderboardIntegrationTest {

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
