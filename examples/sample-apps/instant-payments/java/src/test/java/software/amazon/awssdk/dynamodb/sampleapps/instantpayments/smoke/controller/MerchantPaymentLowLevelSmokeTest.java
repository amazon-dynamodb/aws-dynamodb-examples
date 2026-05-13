package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the same merchant payment smoke tests as {@link MerchantPaymentSmokeTest} but with
 * {@code dynamodb.client-type=low-level} to verify the low-level repository path end to end.
 */
@Tag("smoke")
public class MerchantPaymentLowLevelSmokeTest extends MerchantPaymentSmokeTest {

    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
