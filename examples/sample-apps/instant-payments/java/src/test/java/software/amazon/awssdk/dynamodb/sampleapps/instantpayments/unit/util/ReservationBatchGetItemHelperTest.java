package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.ReservationBatchGetItemHelper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/**
 * Unit tests for {@link ReservationBatchGetItemHelper}.
 *
 * <p>Covers key construction under the account partition, single-table request item wrapping, and
 * response merge by reservation id. These pure helpers are shared by the high-level and low-level
 * repositories, so the assertions pin down the {@code PK}/{@code SK} layout and merge contract that
 * both implementations rely on. Retry orchestration lives in {@code BatchGetItemHelper} and is covered
 * by its own test.
 */
@Tag("unit")
class ReservationBatchGetItemHelperTest {

    private static final String TABLE = "JavaInstantPayments";
    private static final String ACCOUNT_ID = "acc_usd_1";

    @Test
    void buildReservationKeyAttributeMap_whenGivenIds_shouldUsePrefixedPkAndSk() {
        Map<String, AttributeValue> key = ReservationBatchGetItemHelper.buildReservationKeyAttributeMap(
                Account.KEY_PREFIX + ACCOUNT_ID, "res_1");

        assertThat(key.get("PK").s()).isEqualTo("ACCOUNT#acc_usd_1");
        assertThat(key.get("SK").s()).isEqualTo("RESERVATION#res_1");
    }

    @Test
    void buildReservationKeys_whenMultipleIds_shouldPreserveOrderAndDeriveAccountPartition() {
        List<Map<String, AttributeValue>> keys =
                ReservationBatchGetItemHelper.buildReservationKeys(ACCOUNT_ID, List.of("res_1", "res_2"));

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("PK").s()).isEqualTo("ACCOUNT#acc_usd_1");
        assertThat(keys.get(0).get("SK").s()).isEqualTo("RESERVATION#res_1");
        assertThat(keys.get(1).get("SK").s()).isEqualTo("RESERVATION#res_2");
    }

    @Test
    void buildReservationRequestItems_whenSingleTable_shouldKeyByTableNameWithAllReservationKeys() {
        Map<String, KeysAndAttributes> requestItems =
                ReservationBatchGetItemHelper.buildReservationRequestItems(TABLE, ACCOUNT_ID, List.of("res_1", "res_2"));

        assertThat(requestItems).containsOnlyKeys(TABLE);
        List<Map<String, AttributeValue>> keys = requestItems.get(TABLE).keys();
        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).get("SK").s()).isEqualTo("RESERVATION#res_1");
        assertThat(keys.get(1).get("SK").s()).isEqualTo("RESERVATION#res_2");
    }

    @Test
    void mergeReservationBatchGetResponse_whenItemsReturned_shouldAccumulateByReservationId() {
        BatchGetItemResponse response = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of(
                        reservationItem("res_1"),
                        reservationItem("res_2"))))
                .build();
        Map<String, Reservation> accumulator = new LinkedHashMap<>();

        ReservationBatchGetItemHelper.mergeReservationBatchGetResponse(
                TABLE, response, accumulator, ReservationBatchGetItemHelperTest::toReservation);

        assertThat(accumulator).containsOnlyKeys("res_1", "res_2");
        assertThat(accumulator.get("res_1").getReservationId()).isEqualTo("res_1");
    }

    @Test
    void mergeReservationBatchGetResponse_whenTableAbsentFromResponse_shouldLeaveAccumulatorUntouched() {
        BatchGetItemResponse response = BatchGetItemResponse.builder()
                .responses(Map.of())
                .build();
        Map<String, Reservation> accumulator = new LinkedHashMap<>();

        ReservationBatchGetItemHelper.mergeReservationBatchGetResponse(
                TABLE, response, accumulator, ReservationBatchGetItemHelperTest::toReservation);

        assertThat(accumulator).isEmpty();
    }

    /**
     * Builds a minimal batch-get item map carrying only the reservation id attribute.
     *
     * @param reservationId business reservation id stored under {@code reservationId}
     * @return attribute map mimicking one returned reservation row
     */
    private static Map<String, AttributeValue> reservationItem(String reservationId) {
        return Map.of("reservationId", AttributeValue.builder().s(reservationId).build());
    }

    /**
     * Maps a returned attribute map onto a {@link Reservation} carrying just the reservation id.
     *
     * @param item attribute map from a batch-get response
     * @return reservation populated with the {@code reservationId} attribute
     */
    private static Reservation toReservation(Map<String, AttributeValue> item) {
        Reservation reservation = new Reservation();
        reservation.setReservationId(item.get("reservationId").s());
        return reservation;
    }
}
