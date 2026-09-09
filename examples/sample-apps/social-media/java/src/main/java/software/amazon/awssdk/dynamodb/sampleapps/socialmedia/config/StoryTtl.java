package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

/**
 * Seconds added to a story {@code createdAt} to populate {@code expiresAt}.
 *
 * @param value accepted TTL in seconds, 3600 through 604800
 */
public record StoryTtl(long value) {
}
