namespace DynamoDB.InstantPayments.Application.Models;

public sealed record CreateOutboundPaymentInput(
    decimal Amount,
    string MerchantId,
    string Currency,
    string DebtorAccountId,
    string CreditorIban,
    string CreditorName,
    string Reference);