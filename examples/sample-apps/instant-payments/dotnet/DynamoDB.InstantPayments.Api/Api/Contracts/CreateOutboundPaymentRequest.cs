namespace DynamoDB.InstantPayments.Api.Contracts;

public sealed record CreateOutboundPaymentRequest(
    string IdempotencyKey,
    string MerchantId,
    decimal Amount,
    string Currency,
    string DebtorAccountId,
    string CreditorIban,
    string CreditorName,
    string Reference);