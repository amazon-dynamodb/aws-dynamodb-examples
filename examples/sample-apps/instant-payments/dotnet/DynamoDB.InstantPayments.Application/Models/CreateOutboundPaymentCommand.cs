using System;

namespace DynamoDB.InstantPayments.Application.Models;

public sealed record CreateOutboundPaymentCommand(
    OutboundPayment Payment,
    string IdempotencyKey,
    string PayloadHash,
    string EventId,
    int EventSequence,
    DateTimeOffset EventOccurredAtUtc);
