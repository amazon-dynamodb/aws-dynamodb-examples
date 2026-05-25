namespace DynamoDB.InstantPayments.Processor.Configuration;

public sealed class ProcessorOptions
{
    public const string SectionName = "Processor";

    public string Region { get; init; } = "eu-west-1";
    public string PaymentsTableName { get; init; } = string.Empty;
    public string AccountsTableName { get; init; } = string.Empty;
    public string PaymentsStreamArn { get; init; } = string.Empty;

    public decimal AutoAcceptMaxAmount { get; init; } = 1000m;
    public int ExpireAfterMinutes { get; init; } = 15;
    public int StreamPollDelaySeconds { get; init; } = 2;
    public int ExpirationSweepIntervalSeconds { get; init; } = 30;
}
