package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a player id resolves to a profile (or is otherwise valid) but no wallet row
 * exists under that player's partition in DynamoDB.
 */
public class WalletNotFoundException extends RuntimeException {

    /**
     * Creates an exception for the given player id.
     *
     * @param playerId the id whose wallet row was not found
     */
    public WalletNotFoundException(String playerId) {
        super("Wallet not found for player: " + playerId);
    }
}
