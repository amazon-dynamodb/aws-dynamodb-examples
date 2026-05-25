using DynamoDB.InstantPayments.Api.Contracts;
using Microsoft.AspNetCore.Http.HttpResults;

namespace DynamoDB.InstantPayments.Api.Endpoints;

public static class MerchantPaymentEndpoints
{
    public static RouteGroupBuilder MapMerchantPaymentEndpoints(this RouteGroupBuilder group)
    {
        group.MapGet("/{merchantId}/payments", ListMerchantPaymentsAsync)
            .WithName("ListMerchantPayments")
            .Produces<MerchantPaymentsPage>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("List merchant payments");

        group.MapGet("/{merchantId}/payments/state/{state}", ListMerchantPaymentsByStateAsync)
            .WithName("ListMerchantPaymentsByState")
            .Produces<MerchantPaymentsPage>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("List merchant payments by state");

        return group;
    }

    private static Ok<MerchantPaymentsPage> ListMerchantPaymentsAsync(
        string merchantId,
        int? limit,
        bool? scanIndexForward,
        string? nextToken)
    {
        var pageSize = limit is > 0 and <= 100 ? limit.Value : 50;
        var now = DateTimeOffset.UtcNow;
        var items = Enumerable.Range(1, Math.Min(pageSize, 2))
            .Select(i => new MerchantPaymentProjection(
                PaymentId: $"pay-{i}",
                State: "ACCEPTED",
                Version: 1,
                MerchantId: merchantId,
                CorrelationId: Guid.NewGuid().ToString("N"),
                Amount: 100m + i,
                Currency: "EUR",
                CreatedAtUtc: now,
                UpdatedAtUtc: now,
                ReasonCode: null))
            .ToArray();

        return TypedResults.Ok(new MerchantPaymentsPage(items, nextToken is null ? null : null));
    }

    private static Ok<MerchantPaymentsPage> ListMerchantPaymentsByStateAsync(
        string merchantId,
        string state,
        int? limit,
        bool? scanIndexForward,
        string? nextToken)
    {
        var pageSize = limit is > 0 and <= 100 ? limit.Value : 50;
        var normalizedState = state.Trim().ToUpperInvariant();
        var now = DateTimeOffset.UtcNow;
        var items = Enumerable.Range(1, Math.Min(pageSize, 2))
            .Select(i => new MerchantPaymentProjection(
                PaymentId: $"pay-{i}",
                State: normalizedState,
                Version: 1,
                MerchantId: merchantId,
                CorrelationId: Guid.NewGuid().ToString("N"),
                Amount: 100m + i,
                Currency: "EUR",
                CreatedAtUtc: now,
                UpdatedAtUtc: now,
                ReasonCode: null))
            .ToArray();

        return TypedResults.Ok(new MerchantPaymentsPage(items, nextToken is null ? null : null));
    }
}
