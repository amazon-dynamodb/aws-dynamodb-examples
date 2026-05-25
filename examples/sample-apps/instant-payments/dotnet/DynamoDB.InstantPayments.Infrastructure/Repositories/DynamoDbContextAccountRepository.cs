using Amazon.DynamoDBv2.DataModel;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using DynamoDB.InstantPayments.Infrastructure.Mapping;
using Microsoft.Extensions.Options;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.Repositories;

public sealed class DynamoDbContextAccountRepository(
    IDynamoDBContext context,
    IOptions<DynamoDbOptions> options) : IAccountRepository
{
    private LoadConfig LoadOperationConfig => new()
    {
        OverrideTableName = options.Value.AccountsTableName,
        ConsistentRead = true
    };

    public async Task<bool> ExistsAsync(string accountId, CancellationToken cancellationToken)
    {
        var account = await context.LoadAsync<DynamoDbContextAccountEntity>(
            AccountItemKeys.AccountPartitionKey(accountId),
            AccountItemKeys.AccountSortKey(),
            LoadOperationConfig,
            cancellationToken);

        return account is not null;
    }
}
