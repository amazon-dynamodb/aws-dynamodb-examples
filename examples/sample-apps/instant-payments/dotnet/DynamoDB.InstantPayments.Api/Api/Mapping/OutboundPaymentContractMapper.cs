using DynamoDB.InstantPayments.Api.Contracts;
using DynamoDB.InstantPayments.Application.Models;

namespace DynamoDB.InstantPayments.Api.Mapping;

public static class OutboundPaymentContractMapper
{
    public static CreateOutboundPaymentInput ToInput(this CreateOutboundPaymentRequest request) =>
        new(
            request.Amount,
            request.MerchantId,
            request.Currency,
            request.DebtorAccountId,
            request.CreditorIban,
            request.CreditorName,
            request.Reference);

    public static OutboundPaymentResponse ToResponse(this OutboundPaymentCreateResult result) =>
        new(
            result.PaymentId,
            result.CorrelationId,
            result.State,
            result.CreatedAtUtc);
}