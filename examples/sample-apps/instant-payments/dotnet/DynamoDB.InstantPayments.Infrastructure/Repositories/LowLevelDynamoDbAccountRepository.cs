using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using Microsoft.Extensions.Options;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.Repositories;

public sealed class LowLevelDynamoDbAccountRepository(
    IAmazonDynamoDB client,
    IOptions<DynamoDbOptions> options) : IAccountRepository
{
    public async Task<bool> ExistsAsync(string accountId, CancellationToken cancellationToken)
    {
        var response = await client.GetItemAsync(new GetItemRequest
        {
            TableName = options.Value.AccountsTableName,
            Key = new Dictionary<string, AttributeValue>
            {
                [AccountItemAttributes.Pk] = new() { S = AccountItemKeys.AccountPartitionKey(accountId) },
                [AccountItemAttributes.Sk] = new() { S = AccountItemKeys.AccountSortKey() }
            },
            ProjectionExpression = AccountItemAttributes.AccountId,
            ConsistentRead = true
        }, cancellationToken);

        return response.Item.Count > 0;
    }
}
