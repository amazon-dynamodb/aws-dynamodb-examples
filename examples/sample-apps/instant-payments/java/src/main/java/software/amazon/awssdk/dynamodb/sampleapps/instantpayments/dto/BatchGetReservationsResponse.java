package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.util.List;

/**
 * JSON body returned by {@code POST /api/v1/accounts/{accountId}/batch-get-reservations}.
 *
 * <p>{@link #reservations()} lists {@link ReservationResponse} values for keys that existed in
 * DynamoDB. The list follows the order of {@link BatchGetReservationsRequest#reservationIds()} after
 * deduplication before the repository call (first occurrence wins), so repeated request ids do not
 * produce duplicate response rows.
 *
 * <p>{@link #missingReservationIds()} lists reservation identifiers from that same deduplicated
 * ordered set that had no row (HTTP status remains 200 so callers merge partial success safely).
 *
 * <p><strong>Example JSON.</strong>
 * <pre>{@code
 * {
 *   "reservations": [
 *     {
 *       "reservationId": "res_pay_1",
 *       "paymentId": "pay_1",
 *       "amount": 100,
 *       "status": "CONSUMED",
 *       "createdAtUtc": "2026-03-18T10:15:33Z"
 *     }
 *   ],
 *   "missingReservationIds": ["res_unknown"]
 * }
 * }</pre>
 *
 * @param reservations          mapped rows for keys that were found
 * @param missingReservationIds keys from the request (after dedupe) that were not found
 */
public record BatchGetReservationsResponse(
        List<ReservationResponse> reservations,
        List<String> missingReservationIds) {
}
