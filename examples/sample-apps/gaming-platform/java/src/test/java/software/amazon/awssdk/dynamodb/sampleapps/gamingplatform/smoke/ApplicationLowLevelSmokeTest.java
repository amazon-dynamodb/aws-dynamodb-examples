package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the same smoke tests as {@link ApplicationSmokeTest} with
 * {@code dynamodb.client-type=low-level}.
 *
 * <p>Verifies actuator health, favicon handling, OpenAPI docs, and retry strategy beans when the
 * low-level repository implementation is active.
 *
 * <p>Issues HTTP requests through MockMvc against the running application.
 */
@Tag("smoke")
class ApplicationLowLevelSmokeTest extends ApplicationSmokeTest {

    /**
     * Switches the Spring context to the low-level DynamoDB repository implementation.
     * @param registry dynamic property registry for the test context
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
