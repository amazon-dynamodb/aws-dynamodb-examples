package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when a request uses an existing idempotency key with a different payload.
 *
 * <p>This maps to HTTP 409 Conflict. The client must use a new idempotency key
 * for a genuinely different payment.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String idempotencyKey;

    /**
     * @param idempotencyKey client key that already exists with a different payload hash
     */
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency key '%s' was already used with a different request payload".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
