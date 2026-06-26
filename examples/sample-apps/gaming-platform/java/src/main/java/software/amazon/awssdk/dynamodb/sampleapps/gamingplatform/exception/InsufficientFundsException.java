package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a purchase cannot be completed because the player's currency balance
 * is lower than the required cost.
 */
public class InsufficientFundsException extends RuntimeException {

    /**
     * Creates an exception for an unaffordable purchase attempt.
     *
     * @param playerId the player attempting the purchase
     * @param itemId the requested item identifier
     * @param requiredAmount the cost required to complete the purchase
     */
    public InsufficientFundsException(String playerId, String itemId, long requiredAmount) {
        super("Insufficient funds to purchase item " + itemId + " with cost " + requiredAmount);
    }
}
