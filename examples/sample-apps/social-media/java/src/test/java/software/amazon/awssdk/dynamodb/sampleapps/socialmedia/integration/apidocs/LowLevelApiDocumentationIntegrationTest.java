package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.integration.apidocs;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the API documentation scenarios with the low-level (raw async client) wiring, selected
 * by {@code dynamodb.client-type=low-level}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dynamodb.client-type=low-level")
class LowLevelApiDocumentationIntegrationTest extends AbstractApiDocumentationIntegrationTest {
}
