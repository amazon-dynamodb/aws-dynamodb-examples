package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbPlayerStateRepository;

/**
 * Runs {@link LobbyIntegrationTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies the low-level {@link LowLevelDynamoDbPlayerStateRepository} path for batch lobby
 * snapshot reads end to end.
 *
 * <p>Runs against DynamoDB Local via Testcontainers with a full Spring context.
 */
@Tag("integration")
class LobbyLowLevelIntegrationTest extends LobbyIntegrationTest {

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
