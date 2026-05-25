using Amazon;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBStreams;
using DynamoDB.InstantPayments.Processor.Configuration;
using DynamoDB.InstantPayments.Processor.Infrastructure;
using DynamoDB.InstantPayments.Processor.Services;
using Microsoft.Extensions.Options;

var builder = Host.CreateApplicationBuilder(args);

builder.Services
    .AddOptions<ProcessorOptions>()
    .Bind(builder.Configuration.GetSection(ProcessorOptions.SectionName))
    .ValidateOnStart();

builder.Services.AddSingleton<IAmazonDynamoDB>(sp =>
{
    var options = sp.GetRequiredService<IOptions<ProcessorOptions>>().Value;
    return new AmazonDynamoDBClient(RegionEndpoint.GetBySystemName(options.Region));
});

builder.Services.AddSingleton<IAmazonDynamoDBStreams>(sp =>
{
    var options = sp.GetRequiredService<IOptions<ProcessorOptions>>().Value;
    return new AmazonDynamoDBStreamsClient(RegionEndpoint.GetBySystemName(options.Region));
});

builder.Services.AddSingleton<DynamoDbPaymentWriter>();
builder.Services.AddSingleton<PaymentEventProcessor>();
builder.Services.AddHostedService<DynamoDbStreamConsumerService>();
builder.Services.AddHostedService<PaymentExpirationService>();

var host = builder.Build();
await host.RunAsync();
