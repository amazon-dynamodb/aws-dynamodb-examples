namespace DynamoDB.InstantPayments.Infrastructure.Configuration;

public enum DynamoDbRepositoryProvider
{
    LowLevel = 1,
    DocumentModel = 2,
    DynamoDbContext = 3
}