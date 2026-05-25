using Amazon;
using Amazon.DynamoDBv2;
using Amazon.Extensions.NETCore.Setup;
using DynamoDB.InstantPayments.Infrastructure.Configuration;
using Microsoft.Extensions.Options;

namespace DynamoDB.InstantPayments.Infrastructure.DynamoDb.Clients;

public static class DynamoDbClientFactory
{
    public static IAmazonDynamoDB CreateClient(IOptions<DynamoDbOptions> options)
    {
        var region = options.Value.Region;
        var awsOptions = new AWSOptions
        {
            Region = RegionEndpoint.GetBySystemName(region)
        };

        return awsOptions.CreateServiceClient<IAmazonDynamoDB>();
    }
}