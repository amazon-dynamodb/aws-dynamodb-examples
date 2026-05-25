using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.DataModel;
using DynamoDB.InstantPayments.Application.Interfaces;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using DynamoDB.InstantPayments.Infrastructure.DynamoDb.Clients;
using DynamoDB.InstantPayments.Infrastructure.Expressions;
using DynamoDB.InstantPayments.Infrastructure.HostedServices;
using DynamoDB.InstantPayments.Infrastructure.Repositories;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using System;

namespace DynamoDB.InstantPayments.Infrastructure.DependencyInjection;

public static class InfrastructureServiceCollectionExtensions
{
    public static IServiceCollection AddInfrastructure(this IServiceCollection services, IConfiguration configuration)
    {
        services
            .AddOptions<DynamoDbOptions>()
            .Bind(configuration.GetSection(DynamoDbOptions.SectionName))
            .ValidateDataAnnotations()
            .ValidateOnStart();

        services.AddSingleton<IValidateOptions<DynamoDbOptions>, DynamoDbOptionsValidator>();

        services.AddSingleton<IAmazonDynamoDB>(sp =>
        {
            var options = sp.GetRequiredService<IOptions<DynamoDbOptions>>();
            return DynamoDbClientFactory.CreateClient(options);
        });

        services.AddSingleton<IDynamoDBContext>(sp =>
        {
            var client = sp.GetRequiredService<IAmazonDynamoDB>();
            return new DynamoDBContextBuilder()
                .WithDynamoDBClient(() => client)
                .ConfigureContext(config =>
                {
                    config.DerivedTypeAttributeName = OutboundPaymentItemAttributes.EventName;
                })
                .Build();
        });

        services.AddScoped<IOutboundPaymentRepository>(sp =>
        {
            var options = sp.GetRequiredService<IOptions<DynamoDbOptions>>().Value;

            if (!Enum.TryParse<DynamoDbRepositoryProvider>(options.RepositoryProvider, true, out var provider))
            {
                throw new OptionsValidationException(
                    DynamoDbOptions.SectionName,
                    typeof(DynamoDbOptions),
                    [
                        $"DynamoDb:RepositoryProvider '{options.RepositoryProvider}' is unsupported. Supported values: {string.Join(", ", Enum.GetNames<DynamoDbRepositoryProvider>())}."
                    ]);
            }

            return provider switch
            {
                DynamoDbRepositoryProvider.LowLevel => ActivatorUtilities.CreateInstance<LowLevelDynamoDbOutboundPaymentRepository>(sp),
                DynamoDbRepositoryProvider.DocumentModel => ActivatorUtilities.CreateInstance<DocumentModelOutboundPaymentRepository>(sp),
                DynamoDbRepositoryProvider.DynamoDbContext => ActivatorUtilities.CreateInstance<DynamoDbContextOutboundPaymentRepository>(sp),
                _ => throw new OptionsValidationException(
                    DynamoDbOptions.SectionName,
                    typeof(DynamoDbOptions),
                    ["DynamoDb repository provider selection failed."])
            };
        });

        services.AddScoped<IAccountRepository>(sp =>
        {
            var options = sp.GetRequiredService<IOptions<DynamoDbOptions>>().Value;

            if (!Enum.TryParse<DynamoDbRepositoryProvider>(options.RepositoryProvider, true, out var provider))
            {
                throw new OptionsValidationException(
                    DynamoDbOptions.SectionName,
                    typeof(DynamoDbOptions),
                    [
                        $"DynamoDb:RepositoryProvider '{options.RepositoryProvider}' is unsupported. Supported values: {string.Join(", ", Enum.GetNames<DynamoDbRepositoryProvider>())}."
                    ]);
            }

            return provider switch
            {
                DynamoDbRepositoryProvider.LowLevel => ActivatorUtilities.CreateInstance<LowLevelDynamoDbAccountRepository>(sp),
                DynamoDbRepositoryProvider.DocumentModel => ActivatorUtilities.CreateInstance<DocumentModelDynamoDbAccountRepository>(sp),
                DynamoDbRepositoryProvider.DynamoDbContext => ActivatorUtilities.CreateInstance<DynamoDbContextAccountRepository>(sp),
                _ => throw new OptionsValidationException(
                    DynamoDbOptions.SectionName,
                    typeof(DynamoDbOptions),
                    ["DynamoDb account repository provider selection failed."])
            };
        });

        services.AddHostedService<AccountSeedHostedService>();

        return services;
    }
}