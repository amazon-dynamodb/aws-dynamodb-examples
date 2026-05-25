using System;

namespace DynamoDB.InstantPayments.Application.Validation;

public sealed class EntityNotFoundException : Exception
{
    public EntityNotFoundException(string id) : base($"Entity '{id}' was not found.")
    {
    }
}