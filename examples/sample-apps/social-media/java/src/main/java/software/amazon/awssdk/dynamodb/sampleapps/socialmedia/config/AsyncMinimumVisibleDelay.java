package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Operator expectation for how long {@code ASYNC} timeline rows may take to appear.
 *
 * <p>The value is in seconds. Publish does not sleep for it. {@code DynamoDbStreamConsumer}
 * materializes the rows after the source writes.
 *
 * @param value accepted delay in seconds, 1 through 60
 */
public record AsyncMinimumVisibleDelay(int value) {
}
