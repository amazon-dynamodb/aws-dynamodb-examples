namespace DynamoDB.InstantPayments.Infrastructure.Expressions;

public static class OutboundPaymentItemAttributes
{
    public const string Pk = "PK";
    public const string Sk = "SK";
    public const string ItemType = "ItemType";

    public const string PaymentId = "PaymentId";
    public const string Amount = "Amount";
    public const string Currency = "Currency";
    public const string DebtorAccountId = "DebtorAccountId";
    public const string CreditorAccountId = "CreditorAccountId";
    public const string Reference = "Reference";
    public const string PaymentStatus = "PaymentStatus";
    public const string CreatedAtUtc = "CreatedAtUtc";

    public const string EventId = "EventId";
    public const string EventName = "EventName";
    public const string EventSequence = "EventSequence";
    public const string OccurredAtUtc = "OccurredAtUtc";
    public const string EventType = "EventType";
    public const string DataJson = "DataJson";
    public const string Version = "Version";
    public const string EventData = "EventData";
    public const string PaymentData = "PaymentData";
    public const string UpdatedAtUtc = "UpdatedAtUtc";

    public const string IdempotencyKey = "IdempotencyKey";
    public const string PayloadHash = "PayloadHash";
    public const string ResponsePaymentId = "ResponsePaymentId";
    public const string ResponseEventSequence = "ResponseEventSequence";
    public const string ExpiresAtEpochSeconds = "ExpiresAtEpochSeconds";
}