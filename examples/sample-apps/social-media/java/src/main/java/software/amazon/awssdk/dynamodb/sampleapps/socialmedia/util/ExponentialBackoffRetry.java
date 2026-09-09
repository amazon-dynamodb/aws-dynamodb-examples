package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util;

import java.time.Duration;

import software.amazon.awssdk.retries.api.BackoffStrategy;

/**
 * Exponential backoff for leftover {@code BatchWriteItem} {@code UnprocessedItems}.
 *
 * <p>Eight retries after the first call. Each wait uses the SDK full-jitter exponential delay from
 * a 50 ms base to a 1 s cap.
 */
public final class ExponentialBackoffRetry implements RetryStrategy {

    public static final int MAX_UNPROCESSED_RETRIES = 8;

    public static final Duration BASE_DELAY = Duration.ofMillis(50);

    public static final Duration MAX_DELAY = Duration.ofSeconds(1);

    public static final ExponentialBackoffRetry UNPROCESSED_ITEMS = new ExponentialBackoffRetry();

    private static final int SDK_ATTEMPT_OFFSET = 2;

    private final BackoffStrategy backoff;

    /** Builds the unprocessed-item backoff from the named base and cap. */
    private ExponentialBackoffRetry() {
        this.backoff = BackoffStrategy.exponentialDelay(BASE_DELAY, MAX_DELAY);
    }

    /** {@inheritDoc} */
    @Override
    public int maxRetries() {
        return MAX_UNPROCESSED_RETRIES;
    }

    /** {@inheritDoc} */
    @Override
    public Duration delay(int attempt) {
        // Helper attempts are zero-based leftover resubmits. SDK computeDelay(1) is the original call.
        return backoff.computeDelay(attempt + SDK_ATTEMPT_OFFSET);
    }
}
