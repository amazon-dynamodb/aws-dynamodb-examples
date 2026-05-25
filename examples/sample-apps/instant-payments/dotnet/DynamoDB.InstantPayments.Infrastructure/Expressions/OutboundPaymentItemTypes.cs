namespace DynamoDB.InstantPayments.Infrastructure.Expressions;

public static class OutboundPaymentItemTypes
{
    public const string OutboundPaymentEvent = "OUTBOUND_PAYMENT_EVENT";
    public const string OutboundPaymentState = "OUTBOUND_PAYMENT_STATE";
    public const string IdempotencyRecord = "IDEMPOTENCY";
}