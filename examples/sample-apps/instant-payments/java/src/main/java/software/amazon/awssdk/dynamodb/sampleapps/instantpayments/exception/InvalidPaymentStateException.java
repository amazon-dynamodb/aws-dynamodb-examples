package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;

/**
 * Thrown when a supplied payment state string does not match any recognised
 * {@link PaymentState} enum name
 * (after upper-casing the path segment).
 *
 * <p>Maps to HTTP 400 Bad Request with error code {@code INVALID_PAYMENT_STATE}. Handled by
 * {@link GlobalExceptionHandler#handleInvalidPaymentState(InvalidPaymentStateException)}.
 */
public class InvalidPaymentStateException extends RuntimeException {

    /** Unrecognised state value from the client (before normalisation). */
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
