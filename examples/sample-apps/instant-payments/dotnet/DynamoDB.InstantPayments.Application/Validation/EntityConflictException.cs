using System;

namespace DynamoDB.InstantPayments.Application.Validation;

public sealed class EntityConflictException : Exception
{
    public EntityConflictException(string message) : base(message)
    {
    }

    public EntityConflictException(string message, Exception innerException) : base(message, innerException)
    {
    }
}