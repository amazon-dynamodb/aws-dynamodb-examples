using DynamoDB.InstantPayments.Api.Contracts;
using Microsoft.AspNetCore.Http.HttpResults;

namespace DynamoDB.InstantPayments.Api.Endpoints;

public static class AccountEndpoints
{
    public static RouteGroupBuilder MapAccountEndpoints(this RouteGroupBuilder group)
    {
        group.MapGet("/{accountId}", GetAccountAsync)
            .WithName("GetAccount")
            .Produces<GetAccountResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("Get account with reservations");

        group.MapPost("/{accountId}/batch-get-reservations", BatchGetReservationsAsync)
            .WithName("BatchGetReservations")
            .Produces<BatchGetReservationsResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("Batch get reservations");

        return group;
    }

    private static Ok<GetAccountResponse> GetAccountAsync(string accountId)
    {
        var now = DateTimeOffset.UtcNow;
        var reservations = new[]
        {
            new ReservationResponse(
                ReservationId: "res-1",
                PaymentId: "pay-1",
                Amount: 12.34m,
                Status: "ACTIVE",
                CreatedAtUtc: now)
        };

        return TypedResults.Ok(new GetAccountResponse(
            AccountId: accountId,
            Status: "ACTIVE",
            Currency: "EUR",
            CurrentBalance: 1000m,
            AvailableBalance: 987.66m,
            Reservations: reservations));
    }

    private static Ok<BatchGetReservationsResponse> BatchGetReservationsAsync(
        string accountId,
        BatchGetReservationsRequest request)
    {
        var distinctIds = request.ReservationIds
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        var now = DateTimeOffset.UtcNow;
        var reservations = distinctIds
            .Take(2)
            .Select(id => new ReservationResponse(
                ReservationId: id,
                PaymentId: $"pay-{id}",
                Amount: 10m,
                Status: "ACTIVE",
                CreatedAtUtc: now))
            .ToArray();

        var foundIds = reservations.Select(r => r.ReservationId).ToHashSet(StringComparer.Ordinal);
        var missing = distinctIds.Where(id => !foundIds.Contains(id)).ToArray();

        return TypedResults.Ok(new BatchGetReservationsResponse(
            Reservations: reservations,
            MissingReservationIds: missing));
    }
}
