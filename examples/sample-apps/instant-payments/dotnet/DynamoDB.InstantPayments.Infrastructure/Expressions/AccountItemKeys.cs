namespace DynamoDB.InstantPayments.Infrastructure.Expressions;

public static class AccountItemKeys
{
    public static string AccountPartitionKey(string accountId) => $"ACCOUNT#{accountId}";
    public static string AccountSortKey() => "PROFILE";
}
