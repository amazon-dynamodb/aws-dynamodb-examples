namespace DynamoDB.InstantPayments.Processor.Models;

public sealed record PaymentEventEnvelope(
    string PaymentId,
    string Pk,
    string Sk,
    int Sequence,
    string EventName,
    DateTimeOffset OccurredAtUtc,
    PaymentInitiatedPayload Payload);

public sealed record PaymentInitiatedPayload(
    decimal Amount,
    string Currency,
    string DebtorAccountId,
    string CreditorIban,
    string Reference,
    DateTimeOffset CreatedAtUtc);

public sealed record PaymentDecision(
    string EventName,
    string? ReasonCode);
