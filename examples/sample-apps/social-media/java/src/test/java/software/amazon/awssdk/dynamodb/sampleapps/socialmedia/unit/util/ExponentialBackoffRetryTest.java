package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.ExponentialBackoffRetry;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.RetryStrategy;

/**
 * Unit coverage for the unprocessed-item retry budget and delay bounds.
 *
 * <p>Confirms eight retries after the first call and that each wait stays at most the 1 s cap.
 * No DynamoDB service is required.
 */
@Tag("unit")
class ExponentialBackoffRetryTest {

    private static final RetryStrategy STRATEGY = ExponentialBackoffRetry.UNPROCESSED_ITEMS;

    @Test
    void maxRetries_whenUnprocessedItemsStrategy_returnsEight() {
        assertThat(STRATEGY.maxRetries()).isEqualTo(ExponentialBackoffRetry.MAX_UNPROCESSED_RETRIES);
        assertThat(STRATEGY.maxRetries()).isEqualTo(8);
    }

    @Test
    void delay_whenAttemptIsZero_isWithinBackoffBounds() {
        Duration delay = STRATEGY.delay(0);

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(delay).isLessThanOrEqualTo(ExponentialBackoffRetry.MAX_DELAY);
    }

    @Test
    void delay_whenAttemptExceedsBackoffCap_isAtMostOneSecond() {
        Duration delay = STRATEGY.delay(ExponentialBackoffRetry.MAX_UNPROCESSED_RETRIES);

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(delay).isLessThanOrEqualTo(ExponentialBackoffRetry.MAX_DELAY);
        assertThat(delay).isLessThanOrEqualTo(Duration.ofSeconds(1));
    }
}
