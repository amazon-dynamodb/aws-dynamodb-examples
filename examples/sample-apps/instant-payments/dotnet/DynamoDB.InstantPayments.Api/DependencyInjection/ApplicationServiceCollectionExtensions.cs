using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Application.Services;
using Microsoft.Extensions.DependencyInjection;

namespace DynamoDB.InstantPayments.Application.DependencyInjection;

public static class ApplicationServiceCollectionExtensions
{
    public static IServiceCollection AddApplication(this IServiceCollection services)
    {
        services.AddScoped<IOutboundPaymentService, OutboundPaymentService>();
        return services;
    }
}