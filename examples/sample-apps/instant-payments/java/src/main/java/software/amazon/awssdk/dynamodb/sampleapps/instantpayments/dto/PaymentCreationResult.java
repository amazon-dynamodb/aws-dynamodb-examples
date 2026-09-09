package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentService;

/**
 * Internal result wrapper distinguishing new payment creation from idempotent retries.
 *
 * <p>Used by {@link OutboundPaymentService}
 * to tell the controller whether to return HTTP 201 for a new payment or HTTP 200 for a cached idempotent response.
 *
 * @param response the payment response to return to the client
 * @param newlyCreated {@code true} if the payment was just created, {@code false} if
 *                     this is a cached response from a previous identical request
 */
public record PaymentCreationResult(
        CreateOutboundPaymentResponse response,
        boolean newlyCreated) {
}
