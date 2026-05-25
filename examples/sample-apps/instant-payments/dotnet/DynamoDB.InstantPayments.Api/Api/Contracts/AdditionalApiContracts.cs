namespace DynamoDB.InstantPayments.Api.Contracts;

public sealed record ProcessPaymentResponse(
    string PaymentId,
    string State,
    string? ReasonCode);

public sealed record BatchGetReservationsRequest(
    IReadOnlyList<string> ReservationIds);

public sealed record ReservationResponse(
    string ReservationId,
    string PaymentId,
    decimal Amount,
    string Status,
    DateTimeOffset CreatedAtUtc);

public sealed record BatchGetReservationsResponse(
    IReadOnlyList<ReservationResponse> Reservations,
    IReadOnlyList<string> MissingReservationIds);

public sealed record PaymentEventResponse(
    string EventKey,
    string EventType,
    string? ReasonCode,
    string CorrelationId);

public sealed record GetOutboundPaymentResponse(
    string PaymentId,
    string State,
    string CorrelationId,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc,
    string DebtorAccountId,
    string CreditorIban,
    string CreditorName,
    decimal Amount,
    string Currency,
    string IdempotencyKey,
    string? ReasonCode,
    int Version,
    IReadOnlyList<PaymentEventResponse> Events);

public sealed record MerchantPaymentProjection(
    string PaymentId,
    string State,
    long Version,
    string MerchantId,
    string CorrelationId,
    decimal Amount,
    string Currency,
    DateTimeOffset CreatedAtUtc,
    DateTimeOffset UpdatedAtUtc,
    string? ReasonCode);

public sealed record MerchantPaymentsPage(
    IReadOnlyList<MerchantPaymentProjection> Items,
    string? NextToken);

public sealed record GetAccountResponse(
    string AccountId,
    string Status,
    string Currency,
    decimal CurrentBalance,
    decimal AvailableBalance,
    IReadOnlyList<ReservationResponse> Reservations);
