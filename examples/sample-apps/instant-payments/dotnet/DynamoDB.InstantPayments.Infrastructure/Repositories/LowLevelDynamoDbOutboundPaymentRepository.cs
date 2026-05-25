using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Application.Models;
using DynamoDB.InstantPayments.Application.Validation;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using DynamoDB.InstantPayments.Infrastructure.Mapping;
using Microsoft.Extensions.Options;
using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.Repositories;

public sealed class LowLevelDynamoDbOutboundPaymentRepository(
    IAmazonDynamoDB client,
    IOptions<DynamoDbOptions> options) : IOutboundPaymentRepository
{
    public async Task<bool> TryCreateIdempotentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken)
    {
        var ttlSeconds = options.Value.IdempotencyTtlSeconds;
        long? expiresAt = ttlSeconds > 0
            ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds
            : null;

        var paymentItem = OutboundPaymentDataMapper.ToPaymentCreatedEventItem(command);
        var idempotencyItem = OutboundPaymentDataMapper.ToIdempotencyItem(command, expiresAt);

        try
        {
            var transaction = new TransactWriteItemsRequest
            {
                TransactItems =
                [
                    new TransactWriteItem
                    {
                        Put = new Put
                        {
                            TableName = options.Value.TableName,
                            Item = paymentItem,
                            ConditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)",
                            ExpressionAttributeNames = new Dictionary<string, string>
                            {
                                ["#pk"] = OutboundPaymentItemAttributes.Pk,
                                ["#sk"] = OutboundPaymentItemAttributes.Sk
                            }
                        }
                    },
                    new TransactWriteItem
                    {
                        Put = new Put
                        {
                            TableName = options.Value.TableName,
                            Item = idempotencyItem,
                            ConditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)",
                            ExpressionAttributeNames = new Dictionary<string, string>
                            {
                                ["#pk"] = OutboundPaymentItemAttributes.Pk,
                                ["#sk"] = OutboundPaymentItemAttributes.Sk
                            }
                        }
                    }
                ]
            };

            await client.TransactWriteItemsAsync(transaction, cancellationToken);
            return true;
        }
        catch (TransactionCanceledException)
        {
            return false;
        }
    }

    public async Task<OutboundPayment> LoadReplayedPaymentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken)
    {
        var idempotencyKey = new Dictionary<string, AttributeValue>
        {
            [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.IdempotencyPartitionKey(command.IdempotencyKey) },
            [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.IdempotencySortKey() }
        };

        var idempotencyRead = await client.GetItemAsync(new GetItemRequest
        {
            TableName = options.Value.TableName,
            Key = idempotencyKey,
            ConsistentRead = true
        }, cancellationToken);

        if (idempotencyRead.Item.Count == 0)
        {
            throw new EntityConflictException("Could not safely resolve idempotent create request due to transaction race.");
        }

        var persistedHash = idempotencyRead.Item[OutboundPaymentItemAttributes.PayloadHash].S;
        if (!string.Equals(persistedHash, command.PayloadHash, StringComparison.Ordinal))
        {
            throw new IdempotencyConflictException(command.IdempotencyKey);
        }

        var paymentId = idempotencyRead.Item[OutboundPaymentItemAttributes.ResponsePaymentId].S;
        var eventSequence = idempotencyRead.Item.TryGetValue(OutboundPaymentItemAttributes.ResponseEventSequence, out var sequenceEntry)
            ? int.Parse(sequenceEntry.N, System.Globalization.CultureInfo.InvariantCulture)
            : 1;

        var paymentRead = await client.GetItemAsync(new GetItemRequest
        {
            TableName = options.Value.TableName,
            Key = new Dictionary<string, AttributeValue>
            {
                [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.PaymentPartitionKey(paymentId) },
                [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.PaymentEventSortKey(eventSequence) }
            },
            ConsistentRead = true
        }, cancellationToken);

        if (paymentRead.Item.Count == 0)
        {
            throw new EntityConflictException("Idempotency record exists but payment record was not found.");
        }

        return OutboundPaymentDataMapper.FromPaymentEventItem(paymentRead.Item);
    }
}