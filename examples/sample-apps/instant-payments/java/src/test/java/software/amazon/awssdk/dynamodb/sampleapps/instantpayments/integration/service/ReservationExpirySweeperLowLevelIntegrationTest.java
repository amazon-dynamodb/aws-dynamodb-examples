package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the same expiry sweep scenarios as {@link ReservationExpirySweeperIntegrationTest}
 * but with {@code dynamodb.client-type=low-level} so the low-level {@code scanExpiredActiveReservations}
 * scan and {@code releaseReservationTransaction} write are verified end to end against DynamoDB Local.
 */
@Tag("integration")
public class ReservationExpirySweeperLowLevelIntegrationTest extends ReservationExpirySweeperIntegrationTest {

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
