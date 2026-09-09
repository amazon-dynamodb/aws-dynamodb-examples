package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a player lookup by id finds no matching profile in DynamoDB.
 */
public class PlayerNotFoundException extends RuntimeException {

    /**
     * Creates an exception for the given player id.
     *
     * @param playerId the id that was not found
     */
    public PlayerNotFoundException(String playerId) {
        super("Player not found: " + playerId);
    }
}
