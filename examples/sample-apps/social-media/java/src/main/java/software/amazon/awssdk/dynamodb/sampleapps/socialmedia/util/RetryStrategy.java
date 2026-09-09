package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util;

import java.time.Duration;

/**
 * Application-level retry budget for service outcomes the AWS SDK does not retry automatically.
 *
 * <p>{@code BatchWriteItem} can return HTTP {@code 200} with leftover {@code UnprocessedItems}.
 * This strategy decides how many times those leftover writes are resubmitted and how long to wait
 * between attempts. Transport faults on DynamoDB calls stay on the SDK retry configuration.
 */
public interface RetryStrategy {

    /**
     * Maximum number of resubmits after the first service call.
     *
     * @return non-negative retry count, not including the original attempt
     */
    int maxRetries();

    /**
     * Computes how long to wait before the next resubmit.
     *
     * @param attempt zero-based index of the attempt that produced leftover work
     * @return non-negative delay before the next retry
     */
    Duration delay(int attempt);
}
