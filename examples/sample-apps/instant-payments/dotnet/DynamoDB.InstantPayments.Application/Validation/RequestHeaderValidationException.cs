using System;

namespace DynamoDB.InstantPayments.Application.Validation;

public sealed class RequestHeaderValidationException(string message) : Exception(message);