package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

/**
 * Internal result wrapper distinguishing new payment creation from idempotent retries.
 *
 * <p>Used by the service layer to communicate to the controller whether to return
 * HTTP 201 (new) or 200 (cached idempotent response).
 *
 * @param response the payment response to return to the client
 * @param newlyCreated {@code true} if the payment was just created, {@code false} if
 *                     this is a cached response from a previous identical request
 */
public record PaymentCreationResult(
        CreateOutboundPaymentResponse response,
        boolean newlyCreated) {
}
