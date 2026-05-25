using System;

namespace DynamoDB.InstantPayments.Application.Validation;

public sealed class IdempotencyConflictException(string key)
    : Exception($"Idempotency key '{key}' was already used with a different payload.");