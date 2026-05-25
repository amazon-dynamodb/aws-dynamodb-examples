using Amazon.DynamoDBv2.DataModel;
using DynamoDB.InstantPayments.Infrastructure.Expressions;

namespace DynamoDB.InstantPayments.Infrastructure.Mapping;

[DynamoDBTable("__OVERRIDDEN_BY_OPERATION_CONFIG__")]
public sealed class DynamoDbContextOutboundPaymentEntity
{
    [DynamoDBHashKey(OutboundPaymentItemAttributes.Pk)]
    public string Pk { get; init; } = string.Empty;

    [DynamoDBRangeKey(OutboundPaymentItemAttributes.Sk)]
    public string Sk { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.ItemType)]
    public string ItemType { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.EventId)]
    public string EventId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.EventName)]
    public string EventName { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.EventSequence)]
    public int EventSequence { get; init; }

    [DynamoDBProperty(OutboundPaymentItemAttributes.OccurredAtUtc)]
    public string OccurredAtUtc { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentData)]
    public PaymentDdbItem PaymentData { get; init; } = new();

    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentId)]
    public string PaymentId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentStatus)]
    public string PaymentStatus { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreatedAtUtc)]
    public string CreatedAtUtc { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.UpdatedAtUtc)]
    public string UpdatedAtUtc { get; init; } = string.Empty;

    [DynamoDBVersion]
    public long? Version { get; set; }
}

public sealed class PaymentEventDdbItem
{
    [DynamoDBHashKey(OutboundPaymentItemAttributes.Pk)]
    public string EventPartitionKey { get; init; } = string.Empty;

    [DynamoDBRangeKey(OutboundPaymentItemAttributes.Sk)]
    public string EventSortKey { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentId)]
    public string DomainPaymentId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.Version)]
    public int EventVersion { get; init; }

    [DynamoDBProperty(OutboundPaymentItemAttributes.EventName)]
    public string EventName { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreatedAtUtc)]
    public string CreatedAtIsoUtc { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.DataJson)]
    [DynamoDBPolymorphicType("INITIATED", typeof(PaymentInitiatedEventDataDdb))]
    [DynamoDBPolymorphicType("ACCEPTED", typeof(PaymentAcceptedEventDataDdb))]
    [DynamoDBPolymorphicType("CANCELLED", typeof(PaymentCancelledEventDataDdb))]
    public PaymentEventDataDdb EventPayloadData { get; init; } = new PaymentInitiatedEventDataDdb();

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreatedAtUtc)]
    public string CreatedAtUtc { get; init; } = string.Empty;

    [DynamoDBIgnore]
    public string PaymentId => DomainPaymentId;
}

public abstract class PaymentEventDataDdb
{
    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentId)]
    public string PaymentId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreatedAtUtc)]
    public string CreatedAtUtc { get; init; } = string.Empty;
}

public sealed class PaymentInitiatedEventDataDdb : PaymentEventDataDdb
{
    [DynamoDBProperty(OutboundPaymentItemAttributes.Amount)]
    public decimal Amount { get; init; }

    [DynamoDBProperty(OutboundPaymentItemAttributes.Currency)]
    public string Currency { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.DebtorAccountId)]
    public string DebtorAccountId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreditorAccountId)]
    public string CreditorAccountId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.Reference)]
    public string Reference { get; init; } = string.Empty;
}

public sealed class PaymentAcceptedEventDataDdb : PaymentEventDataDdb
{
}

public sealed class PaymentCancelledEventDataDdb : PaymentEventDataDdb
{
}

public sealed class PaymentDdbItem
{
    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentId)]
    public string PaymentId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.Amount)]
    public decimal Amount { get; init; }

    [DynamoDBProperty(OutboundPaymentItemAttributes.Currency)]
    public string Currency { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.DebtorAccountId)]
    public string DebtorAccountId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreditorAccountId)]
    public string CreditorAccountId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.Reference)]
    public string Reference { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.PaymentStatus)]
    public string PaymentStatus { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.CreatedAtUtc)]
    public string CreatedAtUtc { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.UpdatedAtUtc)]
    public string UpdatedAtUtc { get; init; } = string.Empty;
}