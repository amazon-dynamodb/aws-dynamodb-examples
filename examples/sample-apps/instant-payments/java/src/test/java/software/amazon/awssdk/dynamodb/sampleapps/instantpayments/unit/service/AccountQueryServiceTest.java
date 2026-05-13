package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetAccountResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.AccountNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidBatchGetReservationsRequestException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.ReservationStatus;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.AccountPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.BatchGetReservationsResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.AccountQueryService;

/**
 * Unit tests for {@link AccountQueryService}.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class AccountQueryServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Spy
    private PaymentMapper paymentMapper;

    @InjectMocks
    private AccountQueryService accountQueryService;

    @Test
    void getAccount_partitionMissing_throwsAccountNotFound() {
        when(paymentRepository.queryAccountPartition(eq("acc_missing")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> accountQueryService.getAccount("acc_missing"))
                .isInstanceOf(AccountNotFoundException.class)
                .hasFieldOrPropertyWithValue("accountId", "acc_missing");
    }

    @Test
    void getAccount_mapsAccountAndReservations() {
        Instant resCreated = Instant.parse("2026-03-18T10:15:33Z");

        Account account = buildAccount("acc_usd_1", "ACTIVE", "USD",
                new BigDecimal("10000"), new BigDecimal("9900"), 2);

        Reservation r1 = buildReservation("acc_usd_1", "res_pay_1", "pay_1",
                new BigDecimal("50"), ReservationStatus.ACTIVE.name(), resCreated);
        Reservation r2 = buildReservation("acc_usd_1", "res_pay_2", "pay_2",
                new BigDecimal("50"), ReservationStatus.CONSUMED.name(), resCreated);

        when(paymentRepository.queryAccountPartition(eq("acc_usd_1")))
                .thenReturn(CompletableFuture.completedFuture(
                        new AccountPartitionQueryResult(account, List.of(r1, r2))));

        GetAccountResponse response = accountQueryService.getAccount("acc_usd_1");

        assertThat(response.accountId()).isEqualTo("acc_usd_1");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.currentBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(response.availableBalance()).isEqualByComparingTo(new BigDecimal("9900"));

        assertThat(response.reservations()).hasSize(2);
        assertThat(response.reservations().getFirst().reservationId()).isEqualTo("res_pay_1");
        assertThat(response.reservations().getFirst().paymentId()).isEqualTo("pay_1");
        assertThat(response.reservations().getFirst().amount()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(response.reservations().getFirst().status()).isEqualTo("ACTIVE");
        assertThat(response.reservations().getFirst().createdAtUtc()).isEqualTo(resCreated);

        assertThat(response.reservations().get(1).reservationId()).isEqualTo("res_pay_2");
        assertThat(response.reservations().get(1).paymentId()).isEqualTo("pay_2");
        assertThat(response.reservations().get(1).status()).isEqualTo("CONSUMED");
    }

    @Test
    void getAccount_noReservations_returnsEmptyList() {
        Account account = buildAccount("acc_eur_1", "ACTIVE", "EUR",
                new BigDecimal("5000"), new BigDecimal("5000"), 1);

        when(paymentRepository.queryAccountPartition(eq("acc_eur_1")))
                .thenReturn(CompletableFuture.completedFuture(
                        new AccountPartitionQueryResult(account, List.of())));

        GetAccountResponse response = accountQueryService.getAccount("acc_eur_1");

        assertThat(response.accountId()).isEqualTo("acc_eur_1");
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.currentBalance()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(response.availableBalance()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(response.reservations()).isEmpty();
    }

    @Test
    void batchGetReservations_mapsFoundAndMissing() {
        Instant resCreated = Instant.parse("2026-03-18T10:15:33Z");
        Reservation r1 = buildReservation("acc_usd_1", "res_pay_1", "pay_1",
                new BigDecimal("50"), ReservationStatus.ACTIVE.name(), resCreated);
        Reservation r2 = buildReservation("acc_usd_1", "res_pay_2", "pay_2",
                new BigDecimal("50"), ReservationStatus.CONSUMED.name(), resCreated);

        when(paymentRepository.batchGetReservations(eq("acc_usd_1"),
                eq(List.of("res_pay_1", "res_pay_2", "res_missing"))))
                .thenReturn(CompletableFuture.completedFuture(
                        new BatchGetReservationsResult(List.of(r1, r2), List.of("res_missing"))));

        BatchGetReservationsResponse response = accountQueryService.batchGetReservations(
                "acc_usd_1",
                new BatchGetReservationsRequest(List.of("res_pay_1", "res_pay_2", "res_missing")));

        assertThat(response.missingReservationIds()).containsExactly("res_missing");
        assertThat(response.reservations()).hasSize(2);
        assertThat(response.reservations().getFirst().reservationId()).isEqualTo("res_pay_1");
        assertThat(response.reservations().getFirst().paymentId()).isEqualTo("pay_1");
        assertThat(response.reservations().getFirst().amount()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(response.reservations().getFirst().status()).isEqualTo("ACTIVE");
        assertThat(response.reservations().get(1).reservationId()).isEqualTo("res_pay_2");
        assertThat(response.reservations().get(1).status()).isEqualTo("CONSUMED");
    }

    @Test
    void batchGetReservations_allFound_emptyMissing() {
        Instant now = Instant.now();
        Reservation r1 = buildReservation("acc_usd_1", "res_a", "pay_a",
                new BigDecimal("100"), ReservationStatus.ACTIVE.name(), now);

        when(paymentRepository.batchGetReservations(eq("acc_usd_1"), eq(List.of("res_a"))))
                .thenReturn(CompletableFuture.completedFuture(
                        new BatchGetReservationsResult(List.of(r1), List.of())));

        BatchGetReservationsResponse response = accountQueryService.batchGetReservations(
                "acc_usd_1", new BatchGetReservationsRequest(List.of("res_a")));

        assertThat(response.reservations()).hasSize(1);
        assertThat(response.missingReservationIds()).isEmpty();
    }

    @Test
    void batchGetReservations_allMissing_emptyReservations() {
        when(paymentRepository.batchGetReservations(eq("acc_usd_1"), eq(List.of("res_x", "res_y"))))
                .thenReturn(CompletableFuture.completedFuture(
                        new BatchGetReservationsResult(List.of(), List.of("res_x", "res_y"))));

        BatchGetReservationsResponse response = accountQueryService.batchGetReservations(
                "acc_usd_1", new BatchGetReservationsRequest(List.of("res_x", "res_y")));

        assertThat(response.reservations()).isEmpty();
        assertThat(response.missingReservationIds()).containsExactly("res_x", "res_y");
    }

    @Test
    void batchGetReservations_deduplicatesBeforeRepositoryCall() {
        Instant now = Instant.now();
        Reservation reservation = buildReservation("acc_usd_1", "res_a", "pay_a",
                new BigDecimal("100"), ReservationStatus.ACTIVE.name(), now);

        when(paymentRepository.batchGetReservations(eq("acc_usd_1"), eq(List.of("res_a", "res_b"))))
                .thenReturn(CompletableFuture.completedFuture(
                        new BatchGetReservationsResult(List.of(reservation), List.of("res_b"))));

        BatchGetReservationsResponse response = accountQueryService.batchGetReservations(
                "acc_usd_1", new BatchGetReservationsRequest(List.of("res_a", "res_a", "res_b")));

        assertThat(response.reservations()).hasSize(1);
        assertThat(response.reservations().getFirst().reservationId()).isEqualTo("res_a");
        assertThat(response.missingReservationIds()).containsExactly("res_b");
    }

    @Test
    void batchGetReservations_emptyDistinctIds_throwsBeforeRepositoryCall() {
        assertThatThrownBy(() -> accountQueryService.batchGetReservations(
                "acc_usd_1", new BatchGetReservationsRequest(List.of())))
                .isInstanceOf(InvalidBatchGetReservationsRequestException.class)
                .hasMessage("reservationIds must contain at least one distinct reservation id");

        verify(paymentRepository, never()).batchGetReservations(eq("acc_usd_1"), eq(List.of()));
    }

    @Test
    void getAccount_multipleReservationStatuses() {
        Instant now = Instant.now();
        Account account = buildAccount("acc_usd_2", "ACTIVE", "USD",
                new BigDecimal("3000"), new BigDecimal("2800"), 4);

        Reservation active = buildReservation("acc_usd_2", "res_a", "pay_a",
                new BigDecimal("100"), ReservationStatus.ACTIVE.name(), now);
        Reservation consumed = buildReservation("acc_usd_2", "res_b", "pay_b",
                new BigDecimal("50"), ReservationStatus.CONSUMED.name(), now);
        Reservation released = buildReservation("acc_usd_2", "res_c", "pay_c",
                new BigDecimal("50"), ReservationStatus.RELEASED.name(), now);

        when(paymentRepository.queryAccountPartition(eq("acc_usd_2")))
                .thenReturn(CompletableFuture.completedFuture(
                        new AccountPartitionQueryResult(account, List.of(active, consumed, released))));

        GetAccountResponse response = accountQueryService.getAccount("acc_usd_2");

        assertThat(response.reservations()).hasSize(3);
        assertThat(response.reservations().get(0).status()).isEqualTo("ACTIVE");
        assertThat(response.reservations().get(1).status()).isEqualTo("CONSUMED");
        assertThat(response.reservations().get(2).status()).isEqualTo("RELEASED");
    }

    private static Account buildAccount(String accountId, String status, String currency,
                                         BigDecimal currentBalance, BigDecimal availableBalance,
                                         int version) {
        Account account = new Account();
        account.setAccountKey(Account.KEY_PREFIX + accountId);
        account.setEntityKey(Account.KEY_PREFIX + accountId);
        account.setEntityType(Account.ENTITY_TYPE);
        account.setAccountId(accountId);
        account.setStatus(status);
        account.setCurrency(currency);
        account.setCurrentBalance(currentBalance);
        account.setAvailableBalance(availableBalance);
        account.setVersion(version);
        return account;
    }

    private static Reservation buildReservation(String accountId, String reservationId,
                                                 String paymentId, BigDecimal amount,
                                                 String status, Instant createdAtUtc) {
        Reservation reservation = new Reservation();
        reservation.setAccountKey(Account.KEY_PREFIX + accountId);
        reservation.setReservationKey(Reservation.KEY_PREFIX + reservationId);
        reservation.setEntityType(Reservation.ENTITY_TYPE);
        reservation.setReservationId(reservationId);
        reservation.setPaymentId(paymentId);
        reservation.setAmount(amount);
        reservation.setStatus(status);
        reservation.setCreatedAtUtc(createdAtUtc);
        return reservation;
    }
}
