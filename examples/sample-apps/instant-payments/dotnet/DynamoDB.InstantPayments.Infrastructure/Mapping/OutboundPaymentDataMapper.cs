using Amazon.DynamoDBv2.Model;
using DynamoDB.InstantPayments.Application.Models;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using System;
using System.Collections.Generic;

namespace DynamoDB.InstantPayments.Infrastructure.Mapping;

public static class OutboundPaymentDataMapper
{
    public static Dictionary<string, AttributeValue> ToPaymentCreatedEventItem(CreateOutboundPaymentCommand command)
    {
        var payment = command.Payment;

        var eventPayload = new Dictionary<string, AttributeValue>
        {
            [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
            [OutboundPaymentItemAttributes.Amount] = new() { N = payment.Amount.ToString(System.Globalization.CultureInfo.InvariantCulture) },
            [OutboundPaymentItemAttributes.Currency] = new() { S = payment.Currency },
            [OutboundPaymentItemAttributes.DebtorAccountId] = new() { S = payment.DebtorAccountId },
            [OutboundPaymentItemAttributes.CreditorAccountId] = new() { S = payment.CreditorIban },
            [OutboundPaymentItemAttributes.Reference] = new() { S = payment.Reference },
            [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") }
        };

        return new Dictionary<string, AttributeValue>
        {
            [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.PaymentPartitionKey(payment.PaymentId) },
            [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.PaymentEventSortKey(command.EventSequence) },
            [OutboundPaymentItemAttributes.ItemType] = new() { S = OutboundPaymentItemTypes.OutboundPaymentEvent },

            [OutboundPaymentItemAttributes.EventId] = new() { S = command.EventId },
            [OutboundPaymentItemAttributes.EventName] = new() { S = "OutboundPaymentCreated" },
            [OutboundPaymentItemAttributes.EventSequence] = new() { N = command.EventSequence.ToString(System.Globalization.CultureInfo.InvariantCulture) },
            [OutboundPaymentItemAttributes.OccurredAtUtc] = new() { S = command.EventOccurredAtUtc.ToString("O") },

            [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
            [OutboundPaymentItemAttributes.PaymentStatus] = new() { S = payment.Status },
            [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
            [OutboundPaymentItemAttributes.UpdatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
            [OutboundPaymentItemAttributes.EventData] = new()
            {
                M = new Dictionary<string, AttributeValue>
                {
                    [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.PaymentPartitionKey(payment.PaymentId) },
                    [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.PaymentEventSortKey(command.EventSequence) },
                    [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
                    [OutboundPaymentItemAttributes.Version] = new() { N = command.EventSequence.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                    [OutboundPaymentItemAttributes.EventType] = new() { S = "INITIATED" },
                    [OutboundPaymentItemAttributes.DataJson] = new() { M = eventPayload },
                    [OutboundPaymentItemAttributes.Amount] = new() { N = payment.Amount.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                    [OutboundPaymentItemAttributes.Currency] = new() { S = payment.Currency },
                    [OutboundPaymentItemAttributes.DebtorAccountId] = new() { S = payment.DebtorAccountId },
                    [OutboundPaymentItemAttributes.CreditorAccountId] = new() { S = payment.CreditorIban },
                    [OutboundPaymentItemAttributes.Reference] = new() { S = payment.Reference },
                    [OutboundPaymentItemAttributes.PaymentStatus] = new() { S = payment.Status },
                    [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") }
                }
            },
            [OutboundPaymentItemAttributes.PaymentData] = new()
            {
                M = new Dictionary<string, AttributeValue>
                {
                    [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
                    [OutboundPaymentItemAttributes.Amount] = new() { N = payment.Amount.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                    [OutboundPaymentItemAttributes.Currency] = new() { S = payment.Currency },
                    [OutboundPaymentItemAttributes.DebtorAccountId] = new() { S = payment.DebtorAccountId },
                    [OutboundPaymentItemAttributes.CreditorAccountId] = new() { S = payment.CreditorIban },
                    [OutboundPaymentItemAttributes.Reference] = new() { S = payment.Reference },
                    [OutboundPaymentItemAttributes.PaymentStatus] = new() { S = payment.Status },
                    [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
                    [OutboundPaymentItemAttributes.UpdatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") }
                }
            }
        };
    }

    public static Dictionary<string, AttributeValue> ToPaymentStateItem(CreateOutboundPaymentCommand command)
    {
        var payment = command.Payment;

        return new Dictionary<string, AttributeValue>
        {
            [OutboundPaymentItemAttributes.Pk] = new() { S = OutboundPaymentItemKeys.PaymentPartitionKey(payment.PaymentId) },
            [OutboundPaymentItemAttributes.Sk] = new() { S = OutboundPaymentItemKeys.PaymentStateSortKey() },
            [OutboundPaymentItemAttributes.ItemType] = new() { S = OutboundPaymentItemTypes.OutboundPaymentState },
            [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
            [OutboundPaymentItemAttributes.PaymentStatus] = new() { S = payment.Status },
            [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
            [OutboundPaymentItemAttributes.UpdatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
            [OutboundPaymentItemAttributes.PaymentData] = new()
            {
                M = new Dictionary<string, AttributeValue>
                {
                    [OutboundPaymentItemAttributes.PaymentId] = new() { S = payment.PaymentId },
                    [OutboundPaymentItemAttributes.Amount] = new() { N = payment.Amount.ToString(System.Globalization.CultureInfo.InvariantCulture) },
                    [OutboundPaymentItemAttributes.Currency] = new() { S = payment.Currency },
                    [OutboundPaymentItemAttributes.DebtorAccountId] = new() { S = payment.DebtorAccountId },
                    [OutboundPaymentItemAttributes.CreditorAccountId] = new() { S = payment.CreditorIban },
                    [OutboundPaymentItemAttributes.Reference] = new() { S = payment.Reference },
                    [OutboundPaymentItemAttributes.PaymentStatus] = new() { S = payment.Status },
                    [OutboundPaymentItemAttributes.CreatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") },
                    [OutboundPaymentItemAttributes.UpdatedAtUtc] = new() { S = payment.CreatedAtUtc.ToString("O") }
                }
            }
        };
    }

    public static Dictionary<string, AttributeValue> ToIdempotencyItem(
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

    public static OutboundPayment FromPaymentEventItem(Dictionary<string, AttributeValue> item)
    {
        var source = item;

        if (item.TryGetValue(OutboundPaymentItemAttributes.EventData, out var eventDataEntry) && eventDataEntry.M is not null)
        {
            source = eventDataEntry.M.TryGetValue(OutboundPaymentItemAttributes.DataJson, out var dataJsonEntry) && dataJsonEntry.M is not null
                ? dataJsonEntry.M
                : eventDataEntry.M;
        }

        var status = source.TryGetValue(OutboundPaymentItemAttributes.PaymentStatus, out var sourceStatus)
            ? sourceStatus.S
            : item.TryGetValue(OutboundPaymentItemAttributes.PaymentStatus, out var itemStatus)
                ? itemStatus.S
                : "INITIATED";

        var createdAt = source.TryGetValue(OutboundPaymentItemAttributes.CreatedAtUtc, out var sourceCreatedAt)
            ? sourceCreatedAt.S
            : item[OutboundPaymentItemAttributes.CreatedAtUtc].S;

        var paymentId = source.TryGetValue(OutboundPaymentItemAttributes.PaymentId, out var sourcePaymentId)
            ? sourcePaymentId.S
            : item[OutboundPaymentItemAttributes.PaymentId].S;

        return new OutboundPayment(
            PaymentId: paymentId,
            Amount: decimal.Parse(source[OutboundPaymentItemAttributes.Amount].N, System.Globalization.CultureInfo.InvariantCulture),
            Currency: source[OutboundPaymentItemAttributes.Currency].S,
            DebtorAccountId: source[OutboundPaymentItemAttributes.DebtorAccountId].S,
            CreditorIban: source[OutboundPaymentItemAttributes.CreditorAccountId].S,
            CreditorName: string.Empty,
            Reference: source[OutboundPaymentItemAttributes.Reference].S,
            Status: status,
            CreatedAtUtc: DateTimeOffset.Parse(createdAt));
    }
}