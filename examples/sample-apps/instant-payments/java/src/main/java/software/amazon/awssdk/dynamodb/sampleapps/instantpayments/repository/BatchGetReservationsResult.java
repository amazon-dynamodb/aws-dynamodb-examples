package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.util.List;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;

/**
 * Repository outcome for loading named reservation items under one account partition.
 *
 * <p>Each DynamoDB key uses the account partition value on attribute {@code PK} and a reservation
 * sort key on attribute {@code SK} built from {@link Reservation#KEY_PREFIX} plus the business
 * reservation id. Items that exist are returned as {@link Reservation} instances in
 * {@link #reservations()}. Identifiers from the deduplicated request that had no item appear in
 * {@link #missingReservationIds()} in the same order as the deduplicated request list.
 *
 * <p>Callers map {@link Reservation} rows to API DTOs before returning JSON to HTTP clients.
 *
 * @param reservations          {@link Reservation} rows that were loaded successfully
 * @param missingReservationIds reservation ids from the deduplicated request with no matching row
 */
public record BatchGetReservationsResult(
        List<Reservation> reservations,
        List<String> missingReservationIds) {
}
