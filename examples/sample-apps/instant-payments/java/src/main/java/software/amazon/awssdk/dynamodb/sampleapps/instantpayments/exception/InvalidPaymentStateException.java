package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when a supplied payment state string does not match any recognised
 * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState} enum name
 * (after upper-casing the path segment).
 *
 * <p>Maps to HTTP 400 Bad Request with error code {@code INVALID_PAYMENT_STATE}; handled by
 * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler#handleInvalidPaymentState(InvalidPaymentStateException)}.
 */
public class InvalidPaymentStateException extends RuntimeException {

    private final String invalidState;

    /**
     * @param invalidState the unrecognised state value supplied by the client
     */
    public InvalidPaymentStateException(String invalidState) {
        super("Invalid payment state: " + invalidState);
        this.invalidState = invalidState;
    }

    public String getInvalidState() {
        return invalidState;
    }
}
