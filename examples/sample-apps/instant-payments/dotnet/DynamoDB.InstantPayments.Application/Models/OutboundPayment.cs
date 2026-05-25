using System;

namespace DynamoDB.InstantPayments.Application.Models;

public sealed record OutboundPayment(
    string PaymentId,
    decimal Amount,
    string Currency,
    string DebtorAccountId,
    string CreditorIban,
    string CreditorName,
    string Reference,
    string Status,
    DateTimeOffset CreatedAtUtc);
