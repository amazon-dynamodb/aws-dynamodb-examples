package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * End-to-end coverage for the completion deadline guard against DynamoDB Local.
 *
 * <p>DynamoDB TTL deletes a temporary reservation on a best-effort schedule that can lag the deadline by many hours, so
 * a marker can still exist after the hold has logically expired. The complete transaction guards against this by
 * conditioning the reservation consume on {@code expiresAt > now}, so a late completion in that grace window is
 * rejected instead of settling a stale hold.
 *
 * <p>This class forces the grace window deterministically by setting {@code dynamodb.reservation-timeout-seconds} to a
 * negative value. A freshly reserved hold then gets an {@code expiresAt} that is already in the past, so the very next
 * completion attempt fails the guard. The stream listener drives the reserve and complete steps the same way it does in
 * production, so the rejection path is exercised without seeding any pre-processing state by hand.
 *
 * <p>The negative timeout only affects this test context. The within-deadline happy path (a hold consumed and its timer
 * deleted) is covered by {@code completedPayment_whenSettledViaStream_shouldDeleteTemporaryReservationTimer} in
 * {@link ReservationExpiryReleaseIntegrationTest}, which runs with the default timeout.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.reservation-timeout-seconds=-5")
public class ReservationDeadlineGuardIntegrationTest extends AbstractIntegrationTest {

    private static final Duration AWAIT_STATE = Duration.ofSeconds(25);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(400);

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    @Tag("smoke")
    void processPayment_whenHoldExpiredBeforeCompletion_shouldRejectWithReservationExpired() throws Exception {
        String accountId = "acc_usd_1";
        BigDecimal currentBalanceBefore = currentBalance(accountId);

        // The negative timeout makes the reserved hold already past its deadline, so completion must be rejected.
        String paymentId = createPayment(accountId, "100", "Deadline Guard");
        String reservationId = reservationIdForPayment(paymentId);

        await().atMost(AWAIT_STATE)
                .pollInterval(POLL_INTERVAL)
                .alias("payment %s rejected as RESERVATION_EXPIRED".formatted(paymentId))
                .until(() -> "REJECTED".equals(aggregateState(paymentId)));

        // The guard rejected the stale completion rather than consuming the hold.
        assertThat(reasonCode(paymentId)).isEqualTo("RESERVATION_EXPIRED");
        assertThat(reservationStatus(accountId, reservationId)).isNotEqualTo(ReservationStatus.CONSUMED.name());

        // Settlement never debited the ledger balance, so no money moved on the expired hold.
        assertThat(currentBalance(accountId)).isEqualByComparingTo(currentBalanceBefore);
    }

    /**
     * Reads the {@code aggregateState} of a payment stream head row.
     *
     * @param paymentId payment id without the {@code PAYMENT#} prefix
     * @return the stored {@code aggregateState}, or null when the head row is absent
     */
    private String aggregateState(String paymentId) {
        GetItemResponse response = headRow(paymentId);
        if (!response.hasItem()) {
            return null;
        }
        AttributeValue state = response.item().get("aggregateState");
        return state != null ? state.s() : null;
    }

    /**
     * Reads the {@code reasonCode} of a payment stream head row.
     *
     * @param paymentId payment id without the {@code PAYMENT#} prefix
     * @return the stored {@code reasonCode}, or null when absent
     */
    private String reasonCode(String paymentId) {
        GetItemResponse response = headRow(paymentId);
        if (!response.hasItem()) {
            return null;
        }
        AttributeValue reason = response.item().get("reasonCode");
        return reason != null ? reason.s() : null;
    }

    /**
     * Loads the payment stream head row with a strongly consistent read.
     *
     * @param paymentId payment id without the {@code PAYMENT#} prefix
     * @return the get-item response for the head row
     */
    private GetItemResponse headRow(String paymentId) {
        return dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Payment.KEY_PREFIX + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();
    }

    /**
     * Returns the audit reservation status, or null when the row is absent.
     *
     * @param accountId     account id without the {@code ACCOUNT#} prefix
     * @param reservationId reservation id without the {@code RESERVATION#} prefix
     * @return the {@code status} string or null
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
     * Reads the ledger {@code currentBalance} for an account row.
     *
     * @param accountId account id without the {@code ACCOUNT#} prefix
     * @return the stored {@code currentBalance}
     */
    private BigDecimal currentBalance(String accountId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .consistentRead(true)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                        "SK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build()))
                .build()).join();
        assertThat(response.hasItem()).isTrue();
        return new BigDecimal(response.item().get("currentBalance").n());
    }
}

