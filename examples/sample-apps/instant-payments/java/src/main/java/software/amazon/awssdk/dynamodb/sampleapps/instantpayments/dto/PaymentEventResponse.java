package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

/**
 * One append-only domain event from stored history ({@code SK=EVENT#…}).
 *
 * <p>Consumers infer what happened using {@code eventType} (see
 * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType}) and,
 * for rejections, optional {@code reasonCode}; lifecycle context comes from event order and types, not extra transition fields.
 *
 * @param eventKey      stable DynamoDB sort key for this event
 * @param eventType     {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType} name
 * @param reasonCode    rejection reason when applicable
 * @param correlationId tracing id attached to the event
 */
public record PaymentEventResponse(
        String eventKey,
        String eventType,
        String reasonCode,
        String correlationId) {
}
