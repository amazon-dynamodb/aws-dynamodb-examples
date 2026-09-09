package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

 /**
  * End-to-end coverage for the temporary-reservation (TTL marker) release path.
  *
  * <p>An abandoned outbound payment leaves an {@code ACTIVE} audit reservation
  * ({@code RESERVATION#}) plus a short-lived temporary reservation timer ({@code RESERVATION_TEMP#}) that
  * holds funds against the debtor account. When the timer disappears, the DynamoDB Streams
  * {@code REMOVE} record drives
  * {@link OutboundPaymentProcessor#releaseExpiredReservation(String, String)}
  * which restores the available balance and flips the audit row to {@code RELEASED}.
  *
  * <p>Rather than waiting on real TTL deletion (which DynamoDB performs on a best-effort, multi-minute
  * schedule and DynamoDB Local does not emulate deterministically), these tests delete the temporary
  * reservation row directly. A plain {@code DeleteItem} produces the same {@code REMOVE} stream record the
  * listener reacts to, so the behaviour under test is identical while staying fast and deterministic.
  *
  * <p>Tagged {@code integration} at the class level. The primary release happy path additionally
  * carries {@code smoke} so the stream-driven release is exercised under {@code -Dgroups=smoke}, while
  * the no-op and settlement edge cases stay integration-only.
  */
@Tag("integration")
public class ReservationExpiryReleaseIntegrationTest extends AbstractIntegrationTest {

    private static final Duration AWAIT_STATE = Duration.ofSeconds(25);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(400);

    private static final Duration HOLD_PERIOD = Duration.ofSeconds(3);

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    @Tag("smoke")
    void abandonedHold_whenTemporaryReservationRemoved_shouldRestoreFundsAndMarkReservationReleased() {
        String accountId = "acc_usd_1";
        String reservationId = "res_pay_expire_1";
        String paymentId = "pay_expire_1";
        BigDecimal heldAmount = new BigDecimal("150");

        BigDecimal balanceBeforeHold = availableBalance(accountId);

        // Reconstruct the on-DB state an abandoned FUNDS_RESERVED payment leaves behind: an ACTIVE audit
        // reservation, the debtor balance debited by the hold, and a temporary reservation timer row.
        seedReservation(accountId, reservationId, paymentId, heldAmount.toPlainString(), "ACTIVE");
        debitAvailableBalance(accountId, heldAmount);
        seedTemporaryReservation(accountId, reservationId);

        assertThat(availableBalance(accountId)).isEqualByComparingTo(balanceBeforeHold.subtract(heldAmount));

        // Removing the timer is what an expired TTL marker looks like on the stream.
        deleteTemporaryReservation(accountId, reservationId);

        await().atMost(AWAIT_STATE)
                .pollInterval(POLL_INTERVAL)
                .alias("reservation %s released and funds restored".formatted(reservationId))
                .until(() -> "RELEASED".equals(reservationStatus(accountId, reservationId))
                        && balanceBeforeHold.compareTo(availableBalance(accountId)) == 0);

        assertThat(reservationStatus(accountId, reservationId)).isEqualTo("RELEASED");
        assertThat(availableBalance(accountId)).isEqualByComparingTo(balanceBeforeHold);
    }

    @Test
    void settledHold_whenTemporaryReservationRemoved_shouldBeNoOpAndLeaveConsumedReservationUntouched() {
        String accountId = "acc_usd_2";
        String reservationId = "res_pay_consumed_1";
        String paymentId = "pay_consumed_1";

        BigDecimal balanceBefore = availableBalance(accountId);

        // A normally-settled payment leaves a CONSUMED audit row; the timer removal must not double-credit funds.
        seedReservation(accountId, reservationId, paymentId, "75", "CONSUMED");
        seedTemporaryReservation(accountId, reservationId);

        deleteTemporaryReservation(accountId, reservationId);

        // Allow the stream listener to observe and process the REMOVE, then assert nothing changed for a window.
        await().atMost(AWAIT_STATE)
                .during(HOLD_PERIOD)
                .pollInterval(POLL_INTERVAL)
                .alias("consumed reservation %s stays untouched".formatted(reservationId))
                .until(() -> "CONSUMED".equals(reservationStatus(accountId, reservationId))
                        && balanceBefore.compareTo(availableBalance(accountId)) == 0);

        assertThat(reservationStatus(accountId, reservationId)).isEqualTo("CONSUMED");
        assertThat(availableBalance(accountId)).isEqualByComparingTo(balanceBefore);
    }

    @Test
    void completedPayment_whenSettledViaStream_shouldDeleteTemporaryReservationTimer() throws Exception {
        String accountId = "acc_usd_3";

        // Drive a real payment through the reserve -> complete pipeline (stream listener auto-processes it).
        String paymentId = createPayment(accountId, "40", "Timer Cleanup");
        String reservationId = reservationIdForPayment(paymentId);

        await().atMost(AWAIT_STATE)
                .pollInterval(POLL_INTERVAL)
                .alias("payment %s settled and timer removed".formatted(paymentId))
                .until(() -> "CONSUMED".equals(reservationStatus(accountId, reservationId))
                        && !temporaryReservationExists(accountId, reservationId));

        // Settlement consumes the audit hold and atomically deletes its temporary reservation timer.
        assertThat(reservationStatus(accountId, reservationId)).isEqualTo("CONSUMED");
        assertThat(temporaryReservationExists(accountId, reservationId)).isFalse();
    }

    /**
     * Reads the available balance for an account row.
     *
     * @param accountId account id without the {@code ACCOUNT#} prefix
     * @return the stored {@code availableBalance}
     */
    private BigDecimal availableBalance(String accountId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build()))
                .build()).join();
        assertThat(response.hasItem()).isTrue();
        return new BigDecimal(response.item().get("availableBalance").n());
    }

    /**
     * Returns the audit reservation status, or {@code null} when the row is absent.
     *
     * @param accountId     account id without the {@code ACCOUNT#} prefix
     * @param reservationId reservation id without the {@code RESERVATION#} prefix
     * @return the {@code status} string or {@code null}
     */
    private String reservationStatus(String accountId, String reservationId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Reservation.KEY_PREFIX + reservationId).build()))
                .build()).join();
        if (!response.hasItem()) {
            return null;
        }
        AttributeValue status = response.item().get("status");
        return status != null ? status.s() : null;
    }

    /**
     * Reports whether the temporary reservation timer row still exists.
     *
     * @param accountId     account id without the {@code ACCOUNT#} prefix
     * @param reservationId reservation id without the {@code RESERVATION_TEMP#} prefix
     * @return {@code true} when the {@code RESERVATION_TEMP#} row is present
     */
    private boolean temporaryReservationExists(String accountId, String reservationId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Reservation.TEMPORARY_KEY_PREFIX + reservationId).build()))
                .build()).join();
        return response.hasItem();
    }

    /**
     * Lowers the account available balance to mirror funds held by a reservation.
     *
     * @param accountId account id without the {@code ACCOUNT#} prefix
     * @param amount    amount to subtract from {@code availableBalance}
     */
    private void debitAvailableBalance(String accountId, BigDecimal amount) {
        dynamoDbAsyncClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build()))
                .updateExpression("SET availableBalance = availableBalance - :amt, version = version + :one")
                .expressionAttributeValues(Map.of(
                        ":amt", AttributeValue.builder().n(amount.toPlainString()).build(),
                        ":one", AttributeValue.builder().n("1").build()))
                .build()).join();
    }

    /**
     * Writes a temporary reservation timer row ({@code RESERVATION_TEMP#}) under the account partition.
     *
     * @param accountId     account id without the {@code ACCOUNT#} prefix
     * @param reservationId reservation id without the {@code RESERVATION_TEMP#} prefix
     */
    private void seedTemporaryReservation(String accountId, String reservationId) {
        dynamoDbAsyncClient.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Reservation.TEMPORARY_KEY_PREFIX + reservationId).build(),
                        "entityType", AttributeValue.builder().s(Reservation.TEMPORARY_ENTITY_TYPE).build(),
                        "ttl", AttributeValue.builder()
                                .n(Long.toString(Instant.now().plusSeconds(900).getEpochSecond())).build()))
                .build()).join();
    }

    /**
     * Deletes the temporary reservation timer row, emitting the {@code REMOVE} stream record under test.
     *
     * @param accountId     account id without the {@code ACCOUNT#} prefix
     * @param reservationId reservation id without the {@code RESERVATION_TEMP#} prefix
     */
    private void deleteTemporaryReservation(String accountId, String reservationId) {
        dynamoDbAsyncClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Reservation.TEMPORARY_KEY_PREFIX + reservationId).build()))
                .build()).join();
    }
}






