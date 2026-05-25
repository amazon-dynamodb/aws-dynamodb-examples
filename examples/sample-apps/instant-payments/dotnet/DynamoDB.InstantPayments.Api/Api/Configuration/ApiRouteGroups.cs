using DynamoDB.InstantPayments.Api.Endpoints;

namespace DynamoDB.InstantPayments.Api.Configuration;

public static class ApiRouteGroups
{
    public static void MapApiRouteGroups(this IEndpointRouteBuilder app)
    {
        app.MapGroup($"{ApiVersioning.V1}/payments/outbound")
            .WithTags(EndpointTags.OutboundPayments)
            .MapOutboundPaymentEndpoints();

        app.MapGroup($"{ApiVersioning.V1}/merchants")
            .WithTags(EndpointTags.MerchantPayments)
            .MapMerchantPaymentEndpoints();

        app.MapGroup($"{ApiVersioning.V1}/accounts")
            .WithTags(EndpointTags.Accounts)
            .MapAccountEndpoints();
    }
}