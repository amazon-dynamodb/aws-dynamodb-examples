namespace DynamoDB.InstantPayments.Api.Contracts;

public sealed record OutboundPaymentResponse(
    string PaymentId,
    string CorrelationId,
    string State,
    DateTimeOffset CreatedAtUtc);
