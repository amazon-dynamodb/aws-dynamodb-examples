using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.HostedServices;

public sealed class AccountSeedHostedService(
    IAmazonDynamoDB client,
    IOptions<DynamoDbOptions> options,
    ILogger<AccountSeedHostedService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        foreach (var accountId in options.Value.SeedAccountIds.Distinct(StringComparer.Ordinal))
        {
            var trimmed = accountId.Trim();

            var request = new PutItemRequest
            {
                TableName = options.Value.AccountsTableName,
                Item = new Dictionary<string, AttributeValue>
                {
                    [AccountItemAttributes.Pk] = new() { S = AccountItemKeys.AccountPartitionKey(trimmed) },
                    [AccountItemAttributes.Sk] = new() { S = AccountItemKeys.AccountSortKey() },
                    [AccountItemAttributes.ItemType] = new() { S = AccountItemTypes.Account },
                    [AccountItemAttributes.AccountId] = new() { S = trimmed },
                    [AccountItemAttributes.CreatedAtUtc] = new() { S = DateTimeOffset.UtcNow.ToString("O") }
                },
                ConditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)",
                ExpressionAttributeNames = new Dictionary<string, string>
                {
                    ["#pk"] = AccountItemAttributes.Pk,
                    ["#sk"] = AccountItemAttributes.Sk
                }
            };

            try
            {
                await client.PutItemAsync(request, cancellationToken);
                logger.LogInformation("Seeded account {AccountId} in table {TableName}", trimmed, options.Value.AccountsTableName);
            }
            catch (ConditionalCheckFailedException)
            {
                logger.LogDebug("Account {AccountId} already seeded in table {TableName}", trimmed, options.Value.AccountsTableName);
            }
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
