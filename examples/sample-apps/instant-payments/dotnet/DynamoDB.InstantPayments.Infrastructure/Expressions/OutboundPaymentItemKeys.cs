namespace DynamoDB.InstantPayments.Infrastructure.Expressions;

public static class OutboundPaymentItemKeys
{
    public static string PaymentPartitionKey(string paymentId) => $"PAYMENT#{paymentId}";
    public static string PaymentEventSortKey(int sequence) => $"EVENT#{sequence:D10}";
    public static string PaymentStateSortKey() => "STATE";

    public static string IdempotencyPartitionKey(string idempotencyKey) => $"IDEMPOTENCY#{idempotencyKey}";
    public static string IdempotencySortKey() => "OUTBOUND#CREATE";
}