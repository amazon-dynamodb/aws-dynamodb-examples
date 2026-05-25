using Amazon.DynamoDBStreams;
using Amazon.DynamoDBStreams.Model;
using DynamoDB.InstantPayments.Processor.Configuration;
using DynamoDB.InstantPayments.Processor.Models;
using Microsoft.Extensions.Options;

namespace DynamoDB.InstantPayments.Processor.Services;

public sealed class DynamoDbStreamConsumerService(
    IAmazonDynamoDBStreams streams,
    PaymentEventProcessor processor,
    IOptions<ProcessorOptions> options,
    ILogger<DynamoDbStreamConsumerService> logger) : BackgroundService
{
    private readonly Dictionary<string, string> shardIterators = new(StringComparer.Ordinal);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (string.IsNullOrWhiteSpace(options.Value.PaymentsStreamArn))
        {
            logger.LogWarning("Processor:PaymentsStreamArn is not configured. Stream consumer is disabled.");
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var streamDesc = await streams.DescribeStreamAsync(new DescribeStreamRequest
                {
                    StreamArn = options.Value.PaymentsStreamArn
                }, stoppingToken);

                foreach (var shard in streamDesc.StreamDescription.Shards)
                {
                    var shardId = shard.ShardId;

                    if (!shardIterators.TryGetValue(shardId, out var iterator) || string.IsNullOrWhiteSpace(iterator))
                    {
                        var iteratorResp = await streams.GetShardIteratorAsync(new GetShardIteratorRequest
                        {
                            StreamArn = options.Value.PaymentsStreamArn,
                            ShardId = shardId,
                            ShardIteratorType = ShardIteratorType.TRIM_HORIZON
                        }, stoppingToken);

                        iterator = iteratorResp.ShardIterator;
                    }

                    if (string.IsNullOrWhiteSpace(iterator))
                    {
                        continue;
                    }

                    var recordsResp = await streams.GetRecordsAsync(new GetRecordsRequest
                    {
                        ShardIterator = iterator,
                        Limit = 100
                    }, stoppingToken);

                    shardIterators[shardId] = recordsResp.NextShardIterator;

                    foreach (var record in recordsResp.Records)
                    {
                        var evt = TryMapInitiatedEvent(record);
                        if (evt is null)
                        {
                            continue;
                        }

                        await processor.ProcessInitiatedAsync(evt, stoppingToken);
                    }
                }
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Error while consuming DynamoDB stream");
            }

            await Task.Delay(TimeSpan.FromSeconds(options.Value.StreamPollDelaySeconds), stoppingToken);
        }
    }

    private static PaymentEventEnvelope? TryMapInitiatedEvent(Record record)
    {
        var image = record.Dynamodb?.NewImage;
        if (image is null || image.Count == 0)
        {
            return null;
        }

        if (!image.TryGetValue("ItemType", out var itemType) || !string.Equals(itemType.S, "OUTBOUND_PAYMENT_EVENT", StringComparison.Ordinal))
        {
            return null;
        }

        if (!image.TryGetValue("EventName", out var eventName) || !string.Equals(eventName.S, "INITIATED", StringComparison.Ordinal))
        {
            return null;
        }

        if (!image.TryGetValue("PK", out var pk) || !image.TryGetValue("SK", out var sk) ||
            !image.TryGetValue("PaymentId", out var paymentId) || !image.TryGetValue("EventSequence", out var sequence) ||
            !image.TryGetValue("OccurredAtUtc", out var occurredAtUtc) ||
            !image.TryGetValue("DataJson", out var dataJson) || dataJson.M is null)
        {
            return null;
        }

        var payloadMap = dataJson.M;

        if (!payloadMap.TryGetValue("Amount", out var amount) ||
            !payloadMap.TryGetValue("Currency", out var currency) ||
            !payloadMap.TryGetValue("DebtorAccountId", out var debtorAccountId) ||
            !payloadMap.TryGetValue("CreditorAccountId", out var creditorIban) ||
            !payloadMap.TryGetValue("Reference", out var reference) ||
            !payloadMap.TryGetValue("CreatedAtUtc", out var createdAtUtc))
        {
            return null;
        }

        return new PaymentEventEnvelope(
            PaymentId: paymentId.S,
            Pk: pk.S,
            Sk: sk.S,
            Sequence: int.Parse(sequence.N, System.Globalization.CultureInfo.InvariantCulture),
            EventName: eventName.S,
            OccurredAtUtc: DateTimeOffset.Parse(occurredAtUtc.S),
            Payload: new PaymentInitiatedPayload(
                Amount: decimal.Parse(amount.N, System.Globalization.CultureInfo.InvariantCulture),
                Currency: currency.S,
                DebtorAccountId: debtorAccountId.S,
                CreditorIban: creditorIban.S,
                Reference: reference.S,
                CreatedAtUtc: DateTimeOffset.Parse(createdAtUtc.S)));
    }
}
