package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/**
 * Reservation-specific helpers for {@code BatchGetItem} under an account partition.
 *
 * <p>High-level and low-level {@link PaymentRepository}
 * implementations share the same PK/SK layout ({@code ACCOUNT#}{@code accountId} plus
 * {@code RESERVATION#}{@code reservationId}) but differ in how attribute maps become
 * {@link Reservation} instances. This type centralises key construction and response merging.
 * Retry orchestration for unprocessed keys stays in {@link BatchGetItemHelper}.
 */
public final class ReservationBatchGetItemHelper {

    /**
     * Prevents instantiation of this utility type.
     */
    private ReservationBatchGetItemHelper() {
    }

    /**
     * Builds the DynamoDB key attribute map for one reservation item under the given account partition.
     *
     * @param accountPartitionKey partition key value, typically {@link Account#KEY_PREFIX} plus account id
     * @param reservationId       business reservation id (without {@link Reservation#KEY_PREFIX})
     * @return immutable two-attribute map for {@code PK} and {@code SK}
     */
    public static Map<String, AttributeValue> buildReservationKeyAttributeMap(String accountPartitionKey,
                                                                              String reservationId) {
        return Map.of(
                "PK", AttributeValue.builder().s(accountPartitionKey).build(),
                "SK", AttributeValue.builder().s(Reservation.KEY_PREFIX + reservationId).build());
    }

    /**
     * Builds one key map per reservation id for {@code BatchGetItem} under the account partition.
     *
     * @param accountId                  business account id used to derive the partition key
     * @param distinctReservationIds     ids in first-seen distinct order (callers normally dedupe first)
     * @return key list in the same order as {@code distinctReservationIds}, suitable for {@code KeysAndAttributes.keys()}
     */
    public static List<Map<String, AttributeValue>> buildReservationKeys(String accountId,
                                                                         List<String> distinctReservationIds) {
        String accountPartitionKey = Account.KEY_PREFIX + accountId;
        List<Map<String, AttributeValue>> keys = new ArrayList<>(distinctReservationIds.size());
        for (String reservationId : distinctReservationIds) {
            keys.add(buildReservationKeyAttributeMap(accountPartitionKey, reservationId));
        }
        return keys;
    }

    /**
     * Wraps {@link #buildReservationKeys(String, List)} into the single-table {@code requestItems} map
     * for one physical table name.
     *
     * @param tableName                  DynamoDB table name
     * @param accountId                  business account id
     * @param distinctReservationIds     ids in first-seen distinct order
     * @return map with one entry keyed by {@code tableName}
     */
    public static Map<String, KeysAndAttributes> buildReservationRequestItems(String tableName,
                                                                              String accountId,
                                                                              List<String> distinctReservationIds) {
        List<Map<String, AttributeValue>> keys = buildReservationKeys(accountId, distinctReservationIds);
        return Map.of(tableName, KeysAndAttributes.builder().keys(keys).build());
    }

    /**
     * Merges reservation rows from one {@code BatchGetItem} response page into the accumulator map.
     *
     * @param tableName                 table whose {@code responses} entry should be read
     * @param response                  one SDK response page (may include unprocessed keys)
     * @param reservationsByReservationId mutable map keyed by reservation id
     * @param mapItemToReservation        maps each returned item attribute map to a {@link Reservation}
     */
    public static void mergeReservationBatchGetResponse(String tableName,
                                                        BatchGetItemResponse response,
                                                        Map<String, Reservation> reservationsByReservationId,
                                                        Function<Map<String, AttributeValue>, Reservation> mapItemToReservation) {
        List<Map<String, AttributeValue>> items =
                response.responses().getOrDefault(tableName, List.of());
        for (Map<String, AttributeValue> item : items) {
            Reservation reservation = mapItemToReservation.apply(item);
            reservationsByReservationId.put(reservation.getReservationId(), reservation);
        }
    }
}
