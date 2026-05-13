package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration tests for outbound payments using the low-level DynamoDB client.
 *
 * <p>Runs the same scenarios as {@link OutboundPaymentIntegrationTest} but with
 * {@code dynamodb.client-type=low-level} to verify {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.LowLevelDynamoDbPaymentRepository}.
 */
@Tag("integration")
public class OutboundPaymentLowLevelIntegrationTest extends OutboundPaymentIntegrationTest {

    /**
     * Overrides the client type to use the low-level repository implementation.
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
