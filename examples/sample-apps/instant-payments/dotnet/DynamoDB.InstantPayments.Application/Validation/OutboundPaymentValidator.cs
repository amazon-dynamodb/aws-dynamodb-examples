using DynamoDB.InstantPayments.Application.Models;

namespace DynamoDB.InstantPayments.Application.Validation;

public static class OutboundPaymentValidator
{
    public static void ValidateCreate(CreateOutboundPaymentInput input)
    {
        if (input.Amount <= 0)
        {
            throw new EntityValidationException("Amount must be greater than zero.");
        }

        if (string.IsNullOrWhiteSpace(input.Currency) || input.Currency.Trim().Length is not 3)
        {
            throw new EntityValidationException("Currency must be a 3-character ISO code.");
        }

        if (string.IsNullOrWhiteSpace(input.DebtorAccountId))
        {
            throw new EntityValidationException("DebtorAccountId is required.");
        }

        if (string.IsNullOrWhiteSpace(input.CreditorIban))
        {
            throw new EntityValidationException("CreditorIban is required.");
        }

        if (string.IsNullOrWhiteSpace(input.Reference))
        {
            throw new EntityValidationException("Reference is required.");
        }
    }

    public static string ValidateAndNormalizeIdempotencyKey(string idempotencyKey)
    {
        if (string.IsNullOrWhiteSpace(idempotencyKey))
        {
            throw new RequestHeaderValidationException("Idempotency-Key header is required.");
        }

        var trimmed = idempotencyKey.Trim();
        if (trimmed.Length > 128)
        {
            throw new RequestHeaderValidationException("Idempotency-Key header must be 128 characters or fewer.");
        }

        return trimmed;
    }
}