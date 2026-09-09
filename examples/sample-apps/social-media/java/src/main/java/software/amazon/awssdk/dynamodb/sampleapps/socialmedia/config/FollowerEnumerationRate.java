package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Maximum follower-partition Query calls allowed per second for {@code PUBLIC} fan-out.
 *
 * @param value accepted permits per second, 1 through 10000
 */
public record FollowerEnumerationRate(int value) {
}
