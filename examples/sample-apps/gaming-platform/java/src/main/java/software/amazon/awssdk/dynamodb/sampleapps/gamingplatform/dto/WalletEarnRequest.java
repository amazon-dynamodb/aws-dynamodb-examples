package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;

/**
 * Request body for crediting soft currency to a player wallet.
 *
 * @param amount          positive amount of soft currency to credit
 * @param reason          origin of the credit (e.g. {@code MATCH_WIN}, {@code DAILY_LOGIN})
 * @param clientRequestId idempotency key. The same key returns {@code IDEMPOTENT_REPLAY}
 *                        without crediting the player twice
 */
public record WalletEarnRequest(
        @Positive long amount,
        @NotNull CurrencyEarnReason reason,
        @NotBlank String clientRequestId) {
}
