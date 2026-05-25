using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Processor.Configuration;
using Microsoft.Extensions.Options;

namespace DynamoDB.InstantPayments.Processor.Infrastructure;

public sealed class DynamoDbPaymentWriter(
    IAmazonDynamoDB dynamoDb,
    IOptions<ProcessorOptions> options,
    ILogger<DynamoDbPaymentWriter> logger)
{
    public async Task AppendPaymentTransitionEventAsync(
        string paymentPk,
        int nextSequence,
        string paymentId,
        string eventName,
        string? reasonCode,
        CancellationToken cancellationToken)
    {
        var sk = $"EVENT#{nextSequence:D10}";
        var now = DateTimeOffset.UtcNow;

        var payload = new Dictionary<string, AttributeValue>
        {
            ["PaymentId"] = new() { S = paymentId },
            ["CreatedAtUtc"] = new() { S = now.ToString("O") }
        };

        if (!string.IsNullOrWhiteSpace(reasonCode))
        {
            payload["ReasonCode"] = new AttributeValue { S = reasonCode };
        }

        var request = new PutItemRequest
        {
            TableName = options.Value.PaymentsTableName,
            Item = new Dictionary<string, AttributeValue>
            {
                ["PK"] = new() { S = paymentPk },
                ["SK"] = new() { S = sk },
                ["ItemType"] = new() { S = "OUTBOUND_PAYMENT_EVENT" },
                ["PaymentId"] = new() { S = paymentId },
                ["EventId"] = new() { S = Guid.NewGuid().ToString("N") },
                ["EventName"] = new() { S = eventName },
                ["EventSequence"] = new() { N = nextSequence.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                ["OccurredAtUtc"] = new() { S = now.ToString("O") },
                ["Version"] = new() { N = nextSequence.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                ["DataJson"] = new() { M = payload },
                ["CreatedAtUtc"] = new() { S = now.ToString("O") }
            },
            ConditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)",
            ExpressionAttributeNames = new Dictionary<string, string>
            {
                ["#pk"] = "PK",
                ["#sk"] = "SK"
            }
        };

        try
        {
            await dynamoDb.PutItemAsync(request, cancellationToken);
            logger.LogInformation("Appended payment event {EventName} for PaymentId={PaymentId} Seq={Sequence}", eventName, paymentId, nextSequence);
        }
        catch (ConditionalCheckFailedException)
        {
            logger.LogDebug("Payment transition event already exists for PaymentId={PaymentId} Seq={Sequence}", paymentId, nextSequence);
        }
    }

    public async Task ReserveFundsAsync(string accountId, string paymentId, decimal amount, CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var reservationPk = $"ACCOUNT#{accountId}";
        var reservationSk = $"RESERVATION#{paymentId}";

        var request = new PutItemRequest
        {
            TableName = options.Value.AccountsTableName,
            Item = new Dictionary<string, AttributeValue>
            {
                ["PK"] = new() { S = reservationPk },
                ["SK"] = new() { S = reservationSk },
                ["ItemType"] = new() { S = "RESERVATION" },
                ["AccountId"] = new() { S = accountId },
                ["PaymentId"] = new() { S = paymentId },
                ["Amount"] = new() { N = amount.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                ["Status"] = new() { S = "ACTIVE" },
                ["CreatedAtUtc"] = new() { S = now.ToString("O") }
            },
            ConditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)",
            ExpressionAttributeNames = new Dictionary<string, string>
            {
                ["#pk"] = "PK",
                ["#sk"] = "SK"
            }
        };

        try
        {
            await dynamoDb.PutItemAsync(request, cancellationToken);
            logger.LogInformation("Reserved funds for PaymentId={PaymentId} on AccountId={AccountId}", paymentId, accountId);
        }
        catch (ConditionalCheckFailedException)
        {
            logger.LogDebug("Reservation already exists for PaymentId={PaymentId} on AccountId={AccountId}", paymentId, accountId);
        }
    }
}
