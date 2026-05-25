using Amazon.DynamoDBv2.DataModel;
using DynamoDB.InstantPayments.Infrastructure.Expressions;

namespace DynamoDB.InstantPayments.Infrastructure.Mapping;

[DynamoDBTable("__OVERRIDDEN_BY_OPERATION_CONFIG__")]
public sealed class DynamoDbContextIdempotencyEntity
{
    [DynamoDBHashKey(OutboundPaymentItemAttributes.Pk)]
    public string Pk { get; init; } = string.Empty;

    [DynamoDBRangeKey(OutboundPaymentItemAttributes.Sk)]
    public string Sk { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.ItemType)]
    public string ItemType { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.IdempotencyKey)]
    public string IdempotencyKey { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.PayloadHash)]
    public string PayloadHash { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.ResponsePaymentId)]
    public string ResponsePaymentId { get; init; } = string.Empty;

    [DynamoDBProperty(OutboundPaymentItemAttributes.ResponseEventSequence)]
    public int ResponseEventSequence { get; init; }

    [DynamoDBProperty(OutboundPaymentItemAttributes.ExpiresAtEpochSeconds)]
    public long? ExpiresAtEpochSeconds { get; init; }

    [DynamoDBVersion]
    public long? Version { get; set; }
}