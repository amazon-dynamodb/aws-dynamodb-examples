using DynamoDB.InstantPayments.Processor.Configuration;
using DynamoDB.InstantPayments.Processor.Infrastructure;
using DynamoDB.InstantPayments.Processor.Models;
using Microsoft.Extensions.Options;

namespace DynamoDB.InstantPayments.Processor.Services;

public sealed class PaymentEventProcessor(
    DynamoDbPaymentWriter writer,
    IOptions<ProcessorOptions> options,
    ILogger<PaymentEventProcessor> logger)
{
    public async Task ProcessInitiatedAsync(PaymentEventEnvelope initiatedEvent, CancellationToken cancellationToken)
    {
        var decision = Evaluate(initiatedEvent.Payload.Amount, options.Value.AutoAcceptMaxAmount);

        if (string.Equals(decision.EventName, "ACCEPTED", StringComparison.Ordinal))
        {
            await writer.ReserveFundsAsync(
                initiatedEvent.Payload.DebtorAccountId,
                initiatedEvent.PaymentId,
                initiatedEvent.Payload.Amount,
                cancellationToken);
        }

        await writer.AppendPaymentTransitionEventAsync(
            paymentPk: initiatedEvent.Pk,
            nextSequence: initiatedEvent.Sequence + 1,
            paymentId: initiatedEvent.PaymentId,
            eventName: decision.EventName,
            reasonCode: decision.ReasonCode,
            cancellationToken: cancellationToken);

        logger.LogInformation(
            "Processed initiated payment {PaymentId}. Decision={Decision}",
            initiatedEvent.PaymentId,
            decision.EventName);
    }

    public static PaymentDecision Evaluate(decimal amount, decimal autoAcceptMaxAmount)
    {
        if (amount <= autoAcceptMaxAmount)
        {
            return new PaymentDecision("ACCEPTED", null);
        }

        return new PaymentDecision("REJECTED", "AMOUNT_EXCEEDS_AUTO_ACCEPT_LIMIT");
    }
}
