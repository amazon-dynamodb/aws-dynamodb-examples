package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the same concurrency integration test as
 * {@link PaymentProcessingConcurrencyIntegrationTest} with
 * {@code dynamodb.client-type=low-level} so the race is exercised against the
 * low-level repository implementation.
 */
@Tag("integration")
public class PaymentProcessingConcurrencyLowLevelIntegrationTest
        extends PaymentProcessingConcurrencyIntegrationTest {

    /**
     * Overrides the default client type for this test context.
     *
     * @param registry dynamic property registry for test configuration
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
