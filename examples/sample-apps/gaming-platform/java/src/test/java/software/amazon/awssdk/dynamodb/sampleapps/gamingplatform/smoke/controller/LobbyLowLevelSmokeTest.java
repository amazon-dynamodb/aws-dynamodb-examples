package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the same smoke tests as {@link LobbySmokeTest} with {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies the low-level DynamoDB repository path end to end for the scenarios covered by
 * the parent smoke suite.
 *
 * <p>Issues HTTP requests through MockMvc against the running application.
 */
@Tag("smoke")
class LobbyLowLevelSmokeTest extends LobbySmokeTest {

    /**
     * Switches the Spring context to the low-level DynamoDB repository implementation.
     * @param registry dynamic property registry for the test context
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
