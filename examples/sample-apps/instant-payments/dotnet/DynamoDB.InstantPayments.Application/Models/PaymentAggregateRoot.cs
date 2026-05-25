using System;
using System.Collections.Generic;

namespace DynamoDB.InstantPayments.Application.Models;

public sealed class PaymentAggregateRoot
{
    private readonly List<PaymentDomainEvent> _uncommittedEvents = [];

    public string PaymentId { get; private set; } = string.Empty;
    public decimal Amount { get; private set; }
    public string Currency { get; private set; } = string.Empty;
    public string DebtorAccountId { get; private set; } = string.Empty;
    public string CreditorIban { get; private set; } = string.Empty;
    public string CreditorName { get; private set; } = string.Empty;
    public string Reference { get; private set; } = string.Empty;
    public string Status { get; private set; } = string.Empty;
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public int Version { get; private set; }

    public IReadOnlyList<PaymentDomainEvent> UncommittedEvents => _uncommittedEvents;

    public static PaymentAggregateRoot Initiate(
        string paymentId,
        decimal amount,
        string currency,
        string debtorAccountId,
        string creditorIban,
        string creditorName,
        string reference,
        string eventId,
        DateTimeOffset occurredAtUtc)
    {
        var aggregate = new PaymentAggregateRoot();

        aggregate.Apply(new PaymentInitiatedDomainEvent(
            EventId: eventId,
            Sequence: 1,
            OccurredAtUtc: occurredAtUtc,
            PaymentId: paymentId,
            Amount: amount,
            Currency: currency,
            DebtorAccountId: debtorAccountId,
            CreditorIban: creditorIban,
            CreditorName: creditorName,
            Reference: reference), true);

        return aggregate;
    }

    public OutboundPayment ToOutboundPayment() => new(
        PaymentId: PaymentId,
        Amount: Amount,
        Currency: Currency,
        DebtorAccountId: DebtorAccountId,
        CreditorIban: CreditorIban,
        CreditorName: CreditorName,
        Reference: Reference,
        Status: Status,
        CreatedAtUtc: CreatedAtUtc);

    private void Apply(PaymentDomainEvent @event, bool isNew)
    {
        switch (@event)
        {
            case PaymentInitiatedDomainEvent initiated:
                PaymentId = initiated.PaymentId;
                Amount = initiated.Amount;
                Currency = initiated.Currency;
                DebtorAccountId = initiated.DebtorAccountId;
                CreditorIban = initiated.CreditorIban;
                CreditorName = initiated.CreditorName;
                Reference = initiated.Reference;
                Status = "INITIATED";
                CreatedAtUtc = initiated.OccurredAtUtc;
                Version = initiated.Sequence;
                break;
        }

        if (isNew)
        {
            _uncommittedEvents.Add(@event);
        }
    }
}

public abstract record PaymentDomainEvent(
    string EventId,
    int Sequence,
    DateTimeOffset OccurredAtUtc,
    string PaymentId);

public sealed record PaymentInitiatedDomainEvent(
    string EventId,
    int Sequence,
    DateTimeOffset OccurredAtUtc,
    string PaymentId,
    decimal Amount,
    string Currency,
    string DebtorAccountId,
    string CreditorIban,
    string CreditorName,
    string Reference) : PaymentDomainEvent(EventId, Sequence, OccurredAtUtc, PaymentId);
