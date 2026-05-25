using System.ComponentModel.DataAnnotations;

namespace DynamoDB.InstantPayments.Infrastructure.Configuration;

public sealed class DynamoDbOptions
{
    public const string SectionName = "DynamoDb";

    [Required]
    public string Region { get; init; } = string.Empty;

    [Required]
    public string TableName { get; init; } = string.Empty;

    [Required]
    public string AccountsTableName { get; init; } = string.Empty;

    [Required]
    public string RepositoryProvider { get; init; } = string.Empty;

    public string EntityIndexName { get; init; } = "GSI1";

    public int IdempotencyTtlSeconds { get; init; } = 86_400;

    public string[] SeedAccountIds { get; init; } =
    [
        "DEBTOR-0001",
        "DEBTOR-0002",
        "DEBTOR-0003"
    ];
}