package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Maximum continuation-token depth accepted on timeline and inbox reads.
 *
 * @param value accepted cap, 1 through 10000
 */
public record MaxPagesAllowed(int value) {
}
