package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Maximum number of inbox writes per {@code BatchWriteItem} chunk.
 *
 * @param value accepted chunk size, 1 through 25
 */
public record InboxFanoutMax(int value) {
}
