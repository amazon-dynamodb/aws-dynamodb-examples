package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON body for {@code POST /api/v1/accounts/{accountId}/batch-get-reservations}.
 *
 * <p>Validation enforces a non-empty list with at most 100 entries and no blank strings. The HTTP
 * path supplies {@code accountId}. Duplicate values in {@code reservationIds} are allowed and are
 * deduplicated before the repository call, so repeating the same identifier still yields at most
 * one reservation entry or one missing-id entry in the response.
 *
 * <p><strong>Example JSON.</strong>
 * <pre>{@code
 * {
 *   "reservationIds": ["res_pay_111", "res_pay_222"]
 * }
 * }</pre>
 *
 * @param reservationIds reservation identifiers to resolve under the path {@code accountId}
 */
public record BatchGetReservationsRequest(
        @NotNull
        @Size(min = 1, max = 100)
        List<@NotBlank String> reservationIds) {
}
