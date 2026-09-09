package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.util.List;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;

/**
 * All items stored under the account partition key ({@code PK=ACCOUNT#{accountId}}):
 * the {@link Account} row and any {@link Reservation} items.
 *
 * <p>Produced by {@link PaymentRepository#queryAccountPartition(String)} using a single-table
 * {@code Query} (item collection pattern).
 *
 * @param account      the ACCOUNT item, never {@code null} on a non-null result
 * @param reservations reservation items sorted by sort key, may be empty
 */
public record AccountPartitionQueryResult(Account account, List<Reservation> reservations) {
}
