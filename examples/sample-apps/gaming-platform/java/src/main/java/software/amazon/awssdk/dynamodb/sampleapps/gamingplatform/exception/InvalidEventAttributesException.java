package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a recorded game event is missing an attribute that its event type requires.
 *
 * <p>For example, a {@code PVP_MATCH} event must carry a numeric {@code playerScore} so it can be
 * projected onto the leaderboard. Rejecting the request with HTTP 400 prevents silently persisting
 * an event that the leaderboard stream listener would later have to skip.
 */
public class InvalidEventAttributesException extends RuntimeException {

    /**
     * Creates an exception with a client-facing validation message.
     *
     * @param message description of the missing or invalid event attribute
     */
    public InvalidEventAttributesException(String message) {
        super(message);
    }
}
