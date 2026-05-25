using Amazon.DynamoDBv2.DataModel;
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
using System.Threading;
using System.Threading.Tasks;

namespace DynamoDB.InstantPayments.Infrastructure.Repositories;

public sealed class DynamoDbContextOutboundPaymentRepository(
    IDynamoDBContext context,
    IOptions<DynamoDbOptions> options) : IOutboundPaymentRepository
{
    private LoadConfig LoadOperationConfig => new LoadConfig()
    {
        OverrideTableName = options.Value.TableName,
        ConsistentRead = true
    };

    public async Task<bool> TryCreateIdempotentAsync(
        CreateOutboundPaymentCommand command,
        CancellationToken cancellationToken)
    {
        var idempotencyPk = OutboundPaymentItemKeys.IdempotencyPartitionKey(command.IdempotencyKey);
        var idempotencySk = OutboundPaymentItemKeys.IdempotencySortKey();

        var ttlSeconds = options.Value.IdempotencyTtlSeconds;
        long? expiresAt = ttlSeconds > 0
            ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() + ttlSeconds
            : null;

        var paymentEntity = new DynamoDbContextOutboundPaymentEntity
        {
            Pk = OutboundPaymentItemKeys.PaymentPartitionKey(command.Payment.PaymentId),
            Sk = OutboundPaymentItemKeys.PaymentEventSortKey(command.EventSequence),
            ItemType = OutboundPaymentItemTypes.OutboundPaymentEvent,
            EventId = command.EventId,
            EventName = "OutboundPaymentCreated",
            EventSequence = command.EventSequence,
            OccurredAtUtc = command.EventOccurredAtUtc.ToString("O"),
            PaymentId = command.Payment.PaymentId,
            PaymentStatus = command.Payment.Status,
            CreatedAtUtc = command.Payment.CreatedAtUtc.ToString("O"),
            UpdatedAtUtc = command.Payment.CreatedAtUtc.ToString("O"),
            PaymentData = new PaymentDdbItem
            {
                PaymentId = command.Payment.PaymentId,
                Amount = command.Payment.Amount,
                Currency = command.Payment.Currency,
                DebtorAccountId = command.Payment.DebtorAccountId,
                CreditorAccountId = command.Payment.CreditorIban,
                Reference = command.Payment.Reference,
                PaymentStatus = command.Payment.Status,
                CreatedAtUtc = command.Payment.CreatedAtUtc.ToString("O"),
                UpdatedAtUtc = command.Payment.CreatedAtUtc.ToString("O")
            }
        };

        var paymentEventEntity = new PaymentEventDdbItem
        {
            EventPartitionKey = OutboundPaymentItemKeys.PaymentPartitionKey(command.Payment.PaymentId),
            EventSortKey = OutboundPaymentItemKeys.PaymentEventSortKey(command.EventSequence),
            DomainPaymentId = command.Payment.PaymentId,
            EventVersion = command.EventSequence,
            EventName = "INITIATED",
            CreatedAtIsoUtc = command.EventOccurredAtUtc.ToString("O"),
            EventPayloadData = new PaymentInitiatedEventDataDdb
            {
                PaymentId = command.Payment.PaymentId,
                Amount = command.Payment.Amount,
                Currency = command.Payment.Currency,
                DebtorAccountId = command.Payment.DebtorAccountId,
                CreditorAccountId = command.Payment.CreditorIban,
                Reference = command.Payment.Reference,
                CreatedAtUtc = command.Payment.CreatedAtUtc.ToString("O")
            },
            CreatedAtUtc = command.Payment.CreatedAtUtc.ToString("O")
        };

        var idempotencyEntity = new DynamoDbContextIdempotencyEntity
        {
            Pk = idempotencyPk,
            Sk = idempotencySk,
            ItemType = OutboundPaymentItemTypes.IdempotencyRecord,
            IdempotencyKey = command.IdempotencyKey,
            PayloadHash = command.PayloadHash,
            ResponsePaymentId = command.Payment.PaymentId,
            ResponseEventSequence = command.EventSequence,
            ExpiresAtEpochSeconds = expiresAt
        };

        var condition = new Expression
        {
            ExpressionStatement = "attribute_not_exists(PK) AND attribute_not_exists(SK)"
        };

        var transactConfig = new TransactWriteConfig
        {
            OverrideTableName = options.Value.TableName
        };

        var paymentTransact = context.CreateTransactWrite<DynamoDbContextOutboundPaymentEntity>(transactConfig);
        paymentTransact.AddSaveItem(paymentEntity, condition);

        var paymentEventTransact = context.CreateTransactWrite<PaymentEventDdbItem>(transactConfig);
        paymentEventTransact.AddSaveItem(paymentEventEntity, condition);

        var idempotencyTransact = context.CreateTransactWrite<DynamoDbContextIdempotencyEntity>(transactConfig);
        idempotencyTransact.AddSaveItem(idempotencyEntity, condition);

        try
        {
            await context.ExecuteTransactWriteAsync(
            [
                paymentTransact,
                paymentEventTransact,
                idempotencyTransact
            ],
            cancellationToken);
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
        var idempotencyPk = OutboundPaymentItemKeys.IdempotencyPartitionKey(command.IdempotencyKey);
        var idempotencySk = OutboundPaymentItemKeys.IdempotencySortKey();

        var existing = await context.LoadAsync<DynamoDbContextIdempotencyEntity>(
            idempotencyPk,
            idempotencySk,
            LoadOperationConfig,
            cancellationToken);

        if (existing is null)
        {
            throw new EntityConflictException("Could not safely resolve idempotent create request due to transaction race.");
        }

        if (!string.Equals(existing.PayloadHash, command.PayloadHash, StringComparison.Ordinal))
        {
            throw new IdempotencyConflictException(command.IdempotencyKey);
        }

        return await LoadPaymentAsync(existing.ResponsePaymentId, existing.ResponseEventSequence, cancellationToken);
    }

    private async Task<OutboundPayment> LoadPaymentAsync(string paymentId, int eventSequence, CancellationToken cancellationToken)
    {
        var payment = await context.LoadAsync<DynamoDbContextOutboundPaymentEntity>(
            OutboundPaymentItemKeys.PaymentPartitionKey(paymentId),
            OutboundPaymentItemKeys.PaymentEventSortKey(eventSequence),
            LoadOperationConfig,
            cancellationToken);

        if (payment is null)
        {
            throw new EntityConflictException("Idempotency record exists but payment record was not found.");
        }

        var paymentEvent = await context.LoadAsync<PaymentEventDdbItem>(
            OutboundPaymentItemKeys.PaymentPartitionKey(paymentId),
            OutboundPaymentItemKeys.PaymentEventSortKey(eventSequence),
            LoadOperationConfig,
            cancellationToken);

        if (paymentEvent is null)
        {
            throw new EntityConflictException("Payment event record was not found.");
        }

        var initiated = paymentEvent.EventPayloadData as PaymentInitiatedEventDataDdb;

        var source = new PaymentDdbItem
        {
            PaymentId = paymentEvent.DomainPaymentId,
            Amount = initiated?.Amount ?? 0m,
            Currency = initiated?.Currency ?? string.Empty,
            DebtorAccountId = initiated?.DebtorAccountId ?? string.Empty,
            CreditorAccountId = initiated?.CreditorAccountId ?? string.Empty,
            Reference = initiated?.Reference ?? string.Empty,
            PaymentStatus = payment.PaymentStatus.Length > 0 ? payment.PaymentStatus : paymentEvent.EventName,
            CreatedAtUtc = paymentEvent.CreatedAtIsoUtc,
            UpdatedAtUtc = payment.UpdatedAtUtc
        };

        return new OutboundPayment(
            PaymentId: source.PaymentId,
            Amount: source.Amount,
            Currency: source.Currency,
            DebtorAccountId: source.DebtorAccountId,
            CreditorIban: source.CreditorAccountId,
            CreditorName: string.Empty,
            Reference: source.Reference,
            Status: source.PaymentStatus,
            CreatedAtUtc: DateTimeOffset.Parse(source.CreatedAtUtc));
    }
}