package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when a payment cannot be found by its identifier.
 *
 * <p>Maps to HTTP 404 Not Found.
 */
public class PaymentNotFoundException extends RuntimeException {

    /** Business payment id that was not found (without {@code PAYMENT#} prefix). */
    private final String paymentId;

    /**
     * @param paymentId missing business id (without {@code PAYMENT#} prefix)
     */
    public PaymentNotFoundException(String paymentId) {
        super("Payment not found: " + paymentId);
        this.paymentId = paymentId;
    }

    public String getPaymentId() {
        return paymentId;
    }
}
