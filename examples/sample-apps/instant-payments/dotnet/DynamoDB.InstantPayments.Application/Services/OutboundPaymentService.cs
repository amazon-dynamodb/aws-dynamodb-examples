using System;
using System.Globalization;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Application.Models;
using DynamoDB.InstantPayments.Application.Validation;
using Microsoft.Extensions.Logging;

namespace DynamoDB.InstantPayments.Application.Services;

public sealed class OutboundPaymentService(
    IOutboundPaymentRepository repository,
    IAccountRepository accountRepository,
    ILogger<OutboundPaymentService> logger) : IOutboundPaymentService
{
    public async Task<OutboundPaymentCreateResult> CreateAsync(
        CreateOutboundPaymentInput input,
        string idempotencyKey,
        CancellationToken cancellationToken)
    {
        OutboundPaymentValidator.ValidateCreate(input);
        var normalizedKey = OutboundPaymentValidator.ValidateAndNormalizeIdempotencyKey(idempotencyKey);

        var debtorAccountId = input.DebtorAccountId.Trim();
        var debtorExists = await accountRepository.ExistsAsync(debtorAccountId, cancellationToken);
        if (!debtorExists)
        {
            throw new EntityNotFoundException(debtorAccountId);
        }

        var now = DateTimeOffset.UtcNow;
        var eventId = Guid.NewGuid().ToString("N");
        var aggregate = PaymentAggregateRoot.Initiate(
            paymentId: Guid.NewGuid().ToString("N"),
            amount: input.Amount,
            currency: input.Currency.Trim().ToUpperInvariant(),
            debtorAccountId: debtorAccountId,
            creditorIban: input.CreditorIban.Trim(),
            creditorName: input.CreditorName.Trim(),
            reference: input.Reference.Trim(),
            eventId: eventId,
            occurredAtUtc: now);

        var initiatedEvent = (PaymentInitiatedDomainEvent)aggregate.UncommittedEvents.Single();
        var payment = aggregate.ToOutboundPayment();

        var payloadHash = ComputePayloadHash(input);

        var command = new CreateOutboundPaymentCommand(
            payment,
            normalizedKey,
            payloadHash,
            EventId: initiatedEvent.EventId,
            EventSequence: initiatedEvent.Sequence,
            EventOccurredAtUtc: initiatedEvent.OccurredAtUtc);

        OutboundPaymentCreateResult result;
        var created = await repository.TryCreateIdempotentAsync(command, cancellationToken);
        if (created)
        {
            result = new OutboundPaymentCreateResult(
                PaymentId: command.Payment.PaymentId,
                CorrelationId: command.EventId,
                State: OutboundPaymentCreateResultStates.Created,
                CreatedAtUtc: command.Payment.CreatedAtUtc);
        }
        else
        {
            var replayedPayment = await repository.LoadReplayedPaymentAsync(command, cancellationToken);
            result = new OutboundPaymentCreateResult(
                PaymentId: replayedPayment.PaymentId,
                CorrelationId: command.EventId,
                State: OutboundPaymentCreateResultStates.Replayed,
                CreatedAtUtc: replayedPayment.CreatedAtUtc);
        }

        logger.LogInformation(
            "Outbound payment request processed. PaymentId: {PaymentId}, State: {State}, IdempotencyKey: {IdempotencyKey}",
            result.PaymentId,
            result.State,
            normalizedKey);

        return result;
    }

    private static string ComputePayloadHash(CreateOutboundPaymentInput input)
    {
        var normalized = string.Format(
            CultureInfo.InvariantCulture,
            "{0:0.00}|{1}|{2}|{3}|{4}",
            input.Amount,
            input.Currency.Trim().ToUpperInvariant(),
            input.DebtorAccountId.Trim(),
            input.CreditorIban.Trim(),
            input.Reference.Trim());

        var hashBytes = SHA256.HashData(Encoding.UTF8.GetBytes(normalized));
        return Convert.ToHexString(hashBytes);
    }
}