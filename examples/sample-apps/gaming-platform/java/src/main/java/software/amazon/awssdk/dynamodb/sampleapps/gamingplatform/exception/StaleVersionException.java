package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when an optimistic locking check fails because the caller's
 * {@code expectedVersion} does not match the current item version in DynamoDB.
 */
public class StaleVersionException extends RuntimeException {

    /** Player whose version did not match. */
    private final String playerId;

    /** Version the client sent on the conditional write. */
    private final long expectedVersion;

    /**
     * Creates an exception for a version conflict.
     *
     * @param playerId the player whose profile version is stale
     * @param expectedVersion the version the caller expected
     */
    public StaleVersionException(String playerId, long expectedVersion) {
        super("Stale version for player " + playerId + ": expectedVersion=" + expectedVersion);
        this.playerId = playerId;
        this.expectedVersion = expectedVersion;
    }

    public String getPlayerId() {
        return playerId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }
}
