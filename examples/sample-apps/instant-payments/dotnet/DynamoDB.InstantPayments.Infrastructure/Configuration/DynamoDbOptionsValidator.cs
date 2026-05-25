using Microsoft.Extensions.Options;
using System;
using System.Linq;

namespace DynamoDB.InstantPayments.Infrastructure.Configuration;

public sealed class DynamoDbOptionsValidator : IValidateOptions<DynamoDbOptions>
{
    public ValidateOptionsResult Validate(string? name, DynamoDbOptions options)
    {
        if (string.IsNullOrWhiteSpace(options.Region))
        {
            return ValidateOptionsResult.Fail("DynamoDb:Region is required.");
        }

        if (string.IsNullOrWhiteSpace(options.TableName))
        {
            return ValidateOptionsResult.Fail("DynamoDb:TableName is required.");
        }

        if (string.IsNullOrWhiteSpace(options.AccountsTableName))
        {
            return ValidateOptionsResult.Fail("DynamoDb:AccountsTableName is required.");
        }

        if (string.IsNullOrWhiteSpace(options.RepositoryProvider))
        {
            return ValidateOptionsResult.Fail("DynamoDb:RepositoryProvider is required.");
        }

        if (!Enum.TryParse<DynamoDbRepositoryProvider>(options.RepositoryProvider, true, out _))
        {
            return ValidateOptionsResult.Fail(
                $"DynamoDb:RepositoryProvider '{options.RepositoryProvider}' is unsupported. Supported values: {string.Join(", ", Enum.GetNames<DynamoDbRepositoryProvider>())}.");
        }

        if (options.IdempotencyTtlSeconds < 0)
        {
            return ValidateOptionsResult.Fail("DynamoDb:IdempotencyTtlSeconds must be greater than or equal to zero.");
        }

        if (options.SeedAccountIds is null || options.SeedAccountIds.Length == 0 || options.SeedAccountIds.Any(string.IsNullOrWhiteSpace))
        {
            return ValidateOptionsResult.Fail("DynamoDb:SeedAccountIds must contain at least one non-empty account id.");
        }

        return ValidateOptionsResult.Success;
    }
}