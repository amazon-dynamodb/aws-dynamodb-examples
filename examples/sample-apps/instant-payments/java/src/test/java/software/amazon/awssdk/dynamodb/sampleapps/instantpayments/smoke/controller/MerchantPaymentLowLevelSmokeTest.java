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

    /**
     * Overrides the default high-level client with {@code dynamodb.client-type=low-level}.
     *
     * @param registry dynamic property registry for the test context
     */
    @DynamicPropertySource
    static void useLowLevelClient(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.client-type", () -> "low-level");
    }
}
