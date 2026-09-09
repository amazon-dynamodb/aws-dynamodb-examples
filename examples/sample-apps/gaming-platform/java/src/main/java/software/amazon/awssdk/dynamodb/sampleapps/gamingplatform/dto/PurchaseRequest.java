package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request body for an in-game purchase.
 *
 * @param itemId           catalog item identifier
 * @param softCurrencyCost cost in soft currency (must match server-side price)
 * @param clientRequestId  idempotency key to prevent double spend on retries
 */
public record PurchaseRequest(
        @NotBlank String itemId,
        @Positive long softCurrencyCost,
        @NotBlank String clientRequestId) {
}
