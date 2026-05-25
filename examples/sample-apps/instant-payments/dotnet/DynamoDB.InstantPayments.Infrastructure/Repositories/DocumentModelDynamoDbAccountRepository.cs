using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.DocumentModel;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using Microsoft.Extensions.Options;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.Repositories;

public sealed class DocumentModelDynamoDbAccountRepository(
    IAmazonDynamoDB client,
    IOptions<DynamoDbOptions> options) : IAccountRepository
{
    private readonly ITable table = new TableBuilder(client, options.Value.AccountsTableName)
        .AddHashKey(AccountItemAttributes.Pk, DynamoDBEntryType.String)
        .AddRangeKey(AccountItemAttributes.Sk, DynamoDBEntryType.String)
        .Build();

    public async Task<bool> ExistsAsync(string accountId, CancellationToken cancellationToken)
    {
        var account = await table.GetItemAsync(
            new Primitive(AccountItemKeys.AccountPartitionKey(accountId)),
            new Primitive(AccountItemKeys.AccountSortKey()),
            cancellationToken);

        return account is not null;
    }
}
