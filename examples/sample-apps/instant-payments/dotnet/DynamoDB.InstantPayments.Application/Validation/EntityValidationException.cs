using System;

namespace DynamoDB.InstantPayments.Application.Validation;

public sealed class EntityValidationException : Exception
{
    public EntityValidationException(string message) : base(message)
    {
    }
}