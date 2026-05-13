package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

/**
 * Response body for the manual payment processing trigger.
 *
 * @param paymentId the payment that was processed
 * @param state the payment's state after processing (e.g. {@code COMPLETED}, {@code REJECTED})
 * @param reasonCode reason for rejection, or {@code null} if completed successfully
 */
public record ProcessPaymentResponse(
        String paymentId,
        String state,
        String reasonCode) {
}
