package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when an account cannot be found by its identifier.
 *
 * <p>Maps to HTTP 404 Not Found.
 */
public class AccountNotFoundException extends RuntimeException {

    private final String accountId;

    /**
     * @param accountId missing business id (without {@code ACCOUNT#} prefix)
     */
    public AccountNotFoundException(String accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public String getAccountId() {
        return accountId;
    }
}
