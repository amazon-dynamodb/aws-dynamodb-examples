using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.DocumentModel;
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

public sealed class DocumentModelOutboundPaymentRepository(
    IAmazonDynamoDB client,
    IOptions<DynamoDbOptions> options) : IOutboundPaymentRepository
{
    private readonly ITable table = new TableBuilder(client,options.Value.TableName)
        .AddHashKey(OutboundPaymentItemAttributes.Pk, DynamoDBEntryType.String)
        .AddRangeKey(OutboundPaymentItemAttributes.Sk, DynamoDBEntryType.String)
        .Build();

    public async Task<bool> TryCreateIdempotentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken)
    {
        var ttlSeconds = options.Value.IdempotencyTtlSeconds;
        long? expiresAt = ttlSeconds > 0
            ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds
            : null;

        var paymentDocument = ToDocument(ToPaymentCreatedEventItem(command));
        var idempotencyDocument = ToDocument(ToIdempotencyItem(command, expiresAt));

        var condition = new Expression
        {
            ExpressionStatement = "attribute_not_exists(PK) AND attribute_not_exists(SK)"
        };

        var transaction = table.CreateTransactWrite();
        transaction.AddDocumentToPut(paymentDocument, new TransactWriteItemOperationConfig
        {
            ConditionalExpression = condition
        });
        transaction.AddDocumentToPut(idempotencyDocument, new TransactWriteItemOperationConfig
        {
            ConditionalExpression = condition
        });

        try
        {
            await transaction.ExecuteAsync(cancellationToken);
            return true;
        }
        catch (ConditionalCheckFailedException)
        {
            return false;
        }
    }

    public async Task<OutboundPayment> LoadReplayedPaymentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken)
    {
        var idempotencyRead = await table.GetItemAsync(
            new Primitive(OutboundPaymentItemKeys.IdempotencyPartitionKey(command.IdempotencyKey)),
            new Primitive(OutboundPaymentItemKeys.IdempotencySortKey()),
            cancellationToken);

        if (idempotencyRead is null)
        {
            throw new EntityConflictException("Could not safely resolve idempotent create request due to transaction race.");
        }

        var persistedHash = idempotencyRead[OutboundPaymentItemAttributes.PayloadHash].AsString();
        if (!string.Equals(persistedHash, command.PayloadHash, StringComparison.Ordinal))
        {
            throw new IdempotencyConflictException(command.IdempotencyKey);
        }

        var paymentId = idempotencyRead[OutboundPaymentItemAttributes.ResponsePaymentId].AsString();
        var eventSequence = idempotencyRead.TryGetValue(OutboundPaymentItemAttributes.ResponseEventSequence, out var sequenceEntry)
            ? sequenceEntry.AsInt()
            : 1;

        var paymentRead = await table.GetItemAsync(
            new Primitive(OutboundPaymentItemKeys.PaymentPartitionKey(paymentId)),
            new Primitive(OutboundPaymentItemKeys.PaymentEventSortKey(eventSequence)),
            cancellationToken);

        if (paymentRead is null)
        {
            throw new EntityConflictException("Idempotency record exists but payment record was not found.");
        }

        return FromPaymentEventItem(paymentRead.ToAttributeMap());
    }

    private static Dictionary<string, AttributeValue> ToPaymentCreatedEventItem(CreateOutboundPaymentCommand command)
    {
        return OutboundPaymentDataMapper.ToPaymentCreatedEventItem(command);
    }

    private static Dictionary<string, AttributeValue> ToIdempotencyItem(
        CreateOutboundPaymentCommand command,
        long? expiresAtEpochSeconds)
    {
        var item = new Dictionary<string, AttributeValue>
        {
            [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.IdempotencyPartitionKey(command.IdempotencyKey) },
            [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.IdempotencySortKey() },
            [OutboundPaymentItemAttributes.ItemType] = new() { S = OutboundPaymentItemTypes.IdempotencyRecord },
            [OutboundPaymentItemAttributes.IdempotencyKey] = new() { S = command.IdempotencyKey },
            [OutboundPaymentItemAttributes.PayloadHash] = new() { S = command.PayloadHash },
            [OutboundPaymentItemAttributes.ResponsePaymentId] = new() { S = command.Payment.PaymentId },
            [OutboundPaymentItemAttributes.ResponseEventSequence] = new() { N = command.EventSequence.ToString(System.Globalization.CultureInfo.InvariantCulture) }
        };

        if (expiresAtEpochSeconds is not null)
        {
            item[OutboundPaymentItemAttributes.ExpiresAtEpochSeconds] = new AttributeValue
            {
                N = expiresAtEpochSeconds.Value.ToString(System.Globalization.CultureInfo.InvariantCulture)
            };
        }

        return item;
    }

    private static OutboundPayment FromPaymentEventItem(Dictionary<string, AttributeValue> item)
    {
        return OutboundPaymentDataMapper.FromPaymentEventItem(item);
    }

    private static Document ToDocument(Dictionary<string, AttributeValue> item) => Document.FromAttributeMap(item);
}