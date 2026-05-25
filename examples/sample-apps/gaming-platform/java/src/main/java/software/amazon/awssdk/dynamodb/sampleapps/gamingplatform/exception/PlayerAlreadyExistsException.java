package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a player registration conflicts with an existing account
 * that does not match the idempotent replay criteria.
 */
public class PlayerAlreadyExistsException extends RuntimeException {

    /**
     * Creates an exception for the given player id.
     *
     * @param playerId the existing player's id
     */
    public PlayerAlreadyExistsException(String playerId) {
        super("Player already exists with id: " + playerId);
    }
}
