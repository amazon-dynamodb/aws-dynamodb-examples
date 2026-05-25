using System;

namespace DynamoDB.InstantPayments.Application.Models;

public sealed record Account(
    string AccountId,
    DateTimeOffset CreatedAtUtc);
