package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
 * Smoke test covering the end-to-end expired-reservation recovery flow in the full Spring context.
 */
@Tag("smoke")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class ReservationExpirySweeperSmokeTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void sweepOnce_whenExpiredReservationExists_shouldRestoreHeldFunds() {
        String accountId = "acc_smoke_sweep";
        seedAccount(accountId, new BigDecimal("10000"), new BigDecimal("9975"), 1);
        seedReservationWithExpiry(accountId, "res_smoke", "pay_smoke",
                new BigDecimal("25"), ReservationStatus.ACTIVE.name(), Instant.now().getEpochSecond() - 60);

        ReservationExpirySweeper sweeper = new ReservationExpirySweeper(paymentRepository, 60_000L);
        sweeper.sweepOnce();

        AccountPartitionQueryResult result = paymentRepository.queryAccountPartition(accountId).join();
        assertThat(result).isNotNull();
        assertThat(result.account().getAvailableBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(result.account().getVersion()).isEqualTo(2);
        assertThat(result.reservations())
                .singleElement()
                .satisfies(reservation -> assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED.name()));
    }

    /**
     * Seeds an account row directly into DynamoDB Local for the sweep scenario.
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
     * Seeds a reservation row with an explicit expiry so the sweep can select it.
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
}
