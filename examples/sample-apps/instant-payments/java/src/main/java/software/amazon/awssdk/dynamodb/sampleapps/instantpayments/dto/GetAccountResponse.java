package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read model for an account and its associated reservations.
 *
 * <p>Returned by {@code GET /api/v1/accounts/{accountId}}. The account and reservations
 * share the same DynamoDB partition key and are loaded in a single Query
 * (item collection pattern).
 *
 * @param accountId        business account identifier
 * @param status           account status (e.g. {@code ACTIVE})
 * @param currency         ISO 4217 currency code
 * @param currentBalance   posted balance (debits applied after completion)
 * @param availableBalance balance minus active reservations
 * @param reservations     funds holds associated with in-flight payments
 */
public record GetAccountResponse(
        String accountId,
        String status,
        String currency,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        List<ReservationResponse> reservations) {
}
