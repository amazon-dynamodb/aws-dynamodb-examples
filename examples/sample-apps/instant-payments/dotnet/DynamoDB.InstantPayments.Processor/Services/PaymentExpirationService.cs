using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Processor.Configuration;
using DynamoDB.InstantPayments.Processor.Infrastructure;
using Microsoft.Extensions.Options;

namespace DynamoDB.InstantPayments.Processor.Services;

public sealed class PaymentExpirationService(
    IAmazonDynamoDB dynamoDb,
    DynamoDbPaymentWriter writer,
    IOptions<ProcessorOptions> options,
    ILogger<PaymentExpirationService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await SweepAsync(stoppingToken);
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Error while expiring stale initiated payments");
            }

            await Task.Delay(TimeSpan.FromSeconds(options.Value.ExpirationSweepIntervalSeconds), stoppingToken);
        }
    }

    private async Task SweepAsync(CancellationToken cancellationToken)
    {
        Dictionary<string, AttributeValue>? lastKey = null;
        var expireBefore = DateTimeOffset.UtcNow.AddMinutes(-options.Value.ExpireAfterMinutes);

        do
        {
            var scan = await dynamoDb.ScanAsync(new ScanRequest
            {
                TableName = options.Value.PaymentsTableName,
                ExclusiveStartKey = lastKey,
                FilterExpression = "ItemType = :itemType AND EventName = :eventName",
                ExpressionAttributeValues = new Dictionary<string, AttributeValue>
                {
                    [":itemType"] = new() { S = "OUTBOUND_PAYMENT_EVENT" },
                    [":eventName"] = new() { S = "INITIATED" }
                }
            }, cancellationToken);

            foreach (var item in scan.Items)
            {
                if (!item.TryGetValue("PK", out var pk) ||
                    !item.TryGetValue("PaymentId", out var paymentId) ||
                    !item.TryGetValue("EventSequence", out var eventSequence) ||
                    !item.TryGetValue("OccurredAtUtc", out var occurredAtUtc))
                {
                    continue;
                }

                var occurred = DateTimeOffset.Parse(occurredAtUtc.S);
                if (occurred >= expireBefore)
                {
                    continue;
                }

                var hasTerminal = await HasTerminalEventAsync(pk.S, cancellationToken);
                if (hasTerminal)
                {
                    continue;
                }

                await writer.AppendPaymentTransitionEventAsync(
                    paymentPk: pk.S,
                    nextSequence: int.Parse(eventSequence.N, System.Globalization.CultureInfo.InvariantCulture) + 1,
                    paymentId: paymentId.S,
                    eventName: "EXPIRED",
                    reasonCode: "INITIATED_TIMEOUT",
                    cancellationToken: cancellationToken);
            }

            lastKey = scan.LastEvaluatedKey;
        }
        while (lastKey is not null && lastKey.Count > 0);
    }

    private async Task<bool> HasTerminalEventAsync(string paymentPk, CancellationToken cancellationToken)
    {
        var query = await dynamoDb.QueryAsync(new QueryRequest
        {
            TableName = options.Value.PaymentsTableName,
            KeyConditionExpression = "PK = :pk",
            ExpressionAttributeValues = new Dictionary<string, AttributeValue>
            {
                [":pk"] = new() { S = paymentPk }
            },
            ConsistentRead = true
        }, cancellationToken);

        return query.Items.Any(x =>
            x.TryGetValue("EventName", out var eventName) &&
            (string.Equals(eventName.S, "ACCEPTED", StringComparison.Ordinal)
             || string.Equals(eventName.S, "REJECTED", StringComparison.Ordinal)
             || string.Equals(eventName.S, "EXPIRED", StringComparison.Ordinal)));
    }
}
