using System;

namespace DynamoDB.InstantPayments.Application.Models;

public sealed record OutboundPaymentCreateResult(
    string PaymentId,
    string CorrelationId,
    string State,
    DateTimeOffset CreatedAtUtc);

public static class OutboundPaymentCreateResultStates
{
    public const string Created = "CREATED";
    public const string Replayed = "REPLAYED";
}