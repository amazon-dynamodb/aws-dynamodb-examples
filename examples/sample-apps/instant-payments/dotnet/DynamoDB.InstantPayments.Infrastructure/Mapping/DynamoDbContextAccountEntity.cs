using Amazon.DynamoDBv2.DataModel;
using DynamoDB.InstantPayments.Infrastructure.Expressions;

namespace DynamoDB.InstantPayments.Infrastructure.Mapping;

[DynamoDBTable("__OVERRIDDEN_BY_OPERATION_CONFIG__")]
public sealed class DynamoDbContextAccountEntity
{
    [DynamoDBHashKey(AccountItemAttributes.Pk)]
    public string Pk { get; init; } = string.Empty;

    [DynamoDBRangeKey(AccountItemAttributes.Sk)]
    public string Sk { get; init; } = string.Empty;

    [DynamoDBProperty(AccountItemAttributes.ItemType)]
    public string ItemType { get; init; } = string.Empty;

    [DynamoDBProperty(AccountItemAttributes.AccountId)]
    public string AccountId { get; init; } = string.Empty;

    [DynamoDBProperty(AccountItemAttributes.CreatedAtUtc)]
    public string CreatedAtUtc { get; init; } = string.Empty;
}
