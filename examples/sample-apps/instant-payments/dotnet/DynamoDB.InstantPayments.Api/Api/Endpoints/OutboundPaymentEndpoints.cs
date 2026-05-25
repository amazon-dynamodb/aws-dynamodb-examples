using DynamoDB.InstantPayments.Api.Contracts;
using DynamoDB.InstantPayments.Api.Mapping;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Application.Validation;
using Microsoft.AspNetCore.Http.HttpResults;

namespace DynamoDB.InstantPayments.Api.Endpoints;

public static class OutboundPaymentEndpoints
{
    public static RouteGroupBuilder MapOutboundPaymentEndpoints(this RouteGroupBuilder group)
    {
        group.MapPost("/", CreateAsync)
            .WithName("CreateOutboundPayment")
            .Produces<OutboundPaymentResponse>(StatusCodes.Status201Created)
            .Produces<OutboundPaymentResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status400BadRequest)
            .ProducesProblem(StatusCodes.Status409Conflict)
            .WithSummary("Create outbound payment");

        group.MapPost("/{paymentId}/process", ProcessPaymentAsync)
            .WithName("ProcessPayment")
            .Produces<ProcessPaymentResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("Process outbound payment");

        group.MapGet("/{paymentId}", GetOutboundPaymentAsync)
            .WithName("GetOutboundPayment")
            .Produces<GetOutboundPaymentResponse>(StatusCodes.Status200OK)
            .ProducesProblem(StatusCodes.Status404NotFound)
            .ProducesProblem(StatusCodes.Status500InternalServerError)
            .WithSummary("Get outbound payment");

        return group;
    }

    private static async Task<Results<Created<OutboundPaymentResponse>, Ok<OutboundPaymentResponse>>> CreateAsync(
        CreateOutboundPaymentRequest request,
        HttpContext httpContext,
        IOutboundPaymentService service,
        CancellationToken cancellationToken)
    {
        var idempotencyKey = request.IdempotencyKey;
        if (string.IsNullOrWhiteSpace(idempotencyKey))
        {
            if (!httpContext.Request.Headers.TryGetValue("Idempotency-Key", out var values)
                || string.IsNullOrWhiteSpace(values.FirstOrDefault()))
            {
                throw new RequestHeaderValidationException("Idempotency-Key header is required.");
            }

            idempotencyKey = values.First()!;
        }

        var result = await service.CreateAsync(
            request.ToInput(),
            idempotencyKey,
            cancellationToken);

        var response = result.ToResponse();
        return result.State == Application.Models.OutboundPaymentCreateResultStates.Created
            ? TypedResults.Created($"{httpContext.Request.Path}/{response.PaymentId}", response)
            : TypedResults.Ok(response);
    }

    private static Ok<ProcessPaymentResponse> ProcessPaymentAsync(string paymentId)
    {
        return TypedResults.Ok(new ProcessPaymentResponse(
            PaymentId: paymentId,
            State: "ACCEPTED",
            ReasonCode: null));
    }

    private static Ok<GetOutboundPaymentResponse> GetOutboundPaymentAsync(string paymentId)
    {
        var now = DateTimeOffset.UtcNow;
        return TypedResults.Ok(new GetOutboundPaymentResponse(
            PaymentId: paymentId,
            State: "ACCEPTED",
            CorrelationId: Guid.NewGuid().ToString("N"),
            CreatedAtUtc: now,
            UpdatedAtUtc: now,
            DebtorAccountId: "N/A",
            CreditorIban: "N/A",
            CreditorName: "N/A",
            Amount: 0m,
            Currency: "EUR",
            IdempotencyKey: "N/A",
            ReasonCode: null,
            Version: 1,
            Events:
            [
                new PaymentEventResponse(
                    EventKey: "EVENT#0000000001",
                    EventType: "INITIATED",
                    ReasonCode: null,
                    CorrelationId: Guid.NewGuid().ToString("N"))
            ]));
    }
}