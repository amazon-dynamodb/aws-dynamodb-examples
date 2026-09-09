package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Maximum number of timeline writes per {@code BatchWriteItem} chunk.
 *
 * @param value accepted chunk size, 1 through 25
 */
public record TimelineFanoutMax(int value) {
}
