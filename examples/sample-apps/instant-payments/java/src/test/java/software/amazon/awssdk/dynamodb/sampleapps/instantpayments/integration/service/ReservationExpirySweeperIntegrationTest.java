package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.AccountPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.ReservationExpirySweeper;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Integration test for {@link ReservationExpirySweeper} against DynamoDB Local (review item C1).
 *
 * <p>Seeds an account whose {@code availableBalance} is held down by an {@code ACTIVE} reservation whose
 * {@code expiresAt} is already in the past, then drives one sweep and asserts the reservation is
 * {@link ReservationStatus#RELEASED} and the held funds are returned. Also asserts a still-valid
 * (not-yet-expired) hold is left untouched.
 *
 * <p>The background sweeper bean stays disabled (test profile); the test constructs its own sweeper and calls
 * {@link ReservationExpirySweeper#sweepOnce()} so the assertion is deterministic. Streams are disabled so no
 * payment processing races the direct DynamoDB seeding.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class ReservationExpirySweeperIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void sweepOnce_whenReservationExpired_shouldReleaseHoldAndRestoreAvailableBalance() {
        String accountId = "acc_sweep_expired";
        // currentBalance 10000, availableBalance 9950: a 50 hold is outstanding.
        seedAccount(accountId, new BigDecimal("10000"), new BigDecimal("9950"), 1);
        seedReservationWithExpiry(accountId, "res_expired", "pay_expired",
                new BigDecimal("50"), ReservationStatus.ACTIVE.name(), Instant.now().getEpochSecond() - 60);

        ReservationExpirySweeper sweeper = new ReservationExpirySweeper(paymentRepository, 60_000L);
        sweeper.sweepOnce();

        AccountPartitionQueryResult result = paymentRepository.queryAccountPartition(accountId).join();
        assertThat(result).isNotNull();
        // Held funds returned: availableBalance back up to currentBalance.
        assertThat(result.account().getAvailableBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(result.account().getVersion()).isEqualTo(2);

        Reservation released = findReservation(result.reservations(), "res_expired");
        assertThat(released.getStatus()).isEqualTo(ReservationStatus.RELEASED.name());
    }

    @Test
    void sweepOnce_whenReservationNotYetExpired_shouldLeaveHoldActive() {
        String accountId = "acc_sweep_active";
        seedAccount(accountId, new BigDecimal("10000"), new BigDecimal("9950"), 1);
        seedReservationWithExpiry(accountId, "res_active", "pay_active",
                new BigDecimal("50"), ReservationStatus.ACTIVE.name(), Instant.now().getEpochSecond() + 3_600);

        ReservationExpirySweeper sweeper = new ReservationExpirySweeper(paymentRepository, 60_000L);
        sweeper.sweepOnce();

        AccountPartitionQueryResult result = paymentRepository.queryAccountPartition(accountId).join();
        assertThat(result).isNotNull();
        // Untouched: hold still in force, balance still held down, version unchanged.
        assertThat(result.account().getAvailableBalance()).isEqualByComparingTo(new BigDecimal("9950"));
        assertThat(result.account().getVersion()).isEqualTo(1);

        Reservation stillActive = findReservation(result.reservations(), "res_active");
        assertThat(stillActive.getStatus()).isEqualTo(ReservationStatus.ACTIVE.name());
    }

    @Test
    void sweepOnce_whenReservationAlreadyConsumedEvenIfExpired_shouldLeaveBalanceUntouched() {
        String accountId = "acc_sweep_consumed";
        seedAccount(accountId, new BigDecimal("10000"), new BigDecimal("9950"), 1);
        seedReservationWithExpiry(accountId, "res_consumed", "pay_consumed",
                new BigDecimal("50"), ReservationStatus.CONSUMED.name(), Instant.now().getEpochSecond() - 60);

        ReservationExpirySweeper sweeper = new ReservationExpirySweeper(paymentRepository, 60_000L);
        sweeper.sweepOnce();

        AccountPartitionQueryResult result = paymentRepository.queryAccountPartition(accountId).join();
        assertThat(result).isNotNull();
        assertThat(result.account().getAvailableBalance()).isEqualByComparingTo(new BigDecimal("9950"));
        assertThat(result.account().getVersion()).isEqualTo(1);

        Reservation consumed = findReservation(result.reservations(), "res_consumed");
        assertThat(consumed.getStatus()).isEqualTo(ReservationStatus.CONSUMED.name());
    }

    /**
     * Seeds an account row directly into DynamoDB Local for sweep assertions.
     *
     * @param accountId account id without the key prefix
     * @param currentBalance posted balance
     * @param availableBalance balance reduced by outstanding holds
     * @param version optimistic lock version
     */
    private void seedAccount(String accountId, BigDecimal currentBalance, BigDecimal availableBalance, int version) {
        String accountKey = Account.KEY_PREFIX + accountId;
        Map<String, AttributeValue> item = Map.of(
                "PK", AttributeValue.builder().s(accountKey).build(),
                "SK", AttributeValue.builder().s(accountKey).build(),
                "entityType", AttributeValue.builder().s(Account.ENTITY_TYPE).build(),
                "accountId", AttributeValue.builder().s(accountId).build(),
                "status", AttributeValue.builder().s("ACTIVE").build(),
                "currentBalance", AttributeValue.builder().n(currentBalance.toPlainString()).build(),
                "availableBalance", AttributeValue.builder().n(availableBalance.toPlainString()).build(),
                "currency", AttributeValue.builder().s("USD").build(),
                "version", AttributeValue.builder().n(Integer.toString(version)).build());
        dynamoDbAsyncClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build()).join();
    }

    /**
     * Seeds a reservation row with an explicit expiry so the sweep can select or skip it.
     *
     * @param accountId owning account id without the key prefix
     * @param reservationId reservation id without the key prefix
     * @param paymentId linked payment id
     * @param amount held amount
     * @param status reservation status to persist
     * @param expiresAtEpochSecond expiry as epoch seconds
     */
    private void seedReservationWithExpiry(String accountId,
                                           String reservationId,
                                           String paymentId,
                                           BigDecimal amount,
                                           String status,
                                           long expiresAtEpochSecond) {
        Map<String, AttributeValue> item = Map.of(
                "PK", AttributeValue.builder().s(Account.KEY_PREFIX + accountId).build(),
                "SK", AttributeValue.builder().s(Reservation.KEY_PREFIX + reservationId).build(),
                "entityType", AttributeValue.builder().s(Reservation.ENTITY_TYPE).build(),
                "reservationId", AttributeValue.builder().s(reservationId).build(),
                "paymentId", AttributeValue.builder().s(paymentId).build(),
                "amount", AttributeValue.builder().n(amount.toPlainString()).build(),
                "status", AttributeValue.builder().s(status).build(),
                "createdAtUtc", AttributeValue.builder().s(Instant.now().toString()).build(),
                "expiresAt", AttributeValue.builder().n(Long.toString(expiresAtEpochSecond)).build());
        dynamoDbAsyncClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build()).join();
    }

    /**
     * Finds a reservation by id in a partition query result.
     *
     * @param reservations reservations returned for the account
     * @param reservationId reservation id to locate
     * @return the matching reservation
     * @throws AssertionError when no reservation matches
     */
    private static Reservation findReservation(List<Reservation> reservations, String reservationId) {
        return reservations.stream()
                .filter(r -> reservationId.equals(r.getReservationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Reservation not found: " + reservationId));
    }
}
