package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a purchase cannot be completed because the player's currency balance
 * is lower than the required cost.
 */
public class InsufficientFundsException extends RuntimeException {

    /**
     * Creates an exception with balance details.
     *
     * @param playerId  the player attempting the purchase
     * @param requiredAmount the cost of the item
     * @param availableBalance the player's current balance
     */
    public InsufficientFundsException(String playerId, long requiredAmount, long availableBalance) {
        super("Insufficient funds for player " + playerId
                + ": required=" + requiredAmount + ", available=" + availableBalance);
    }
}
