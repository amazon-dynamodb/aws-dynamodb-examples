package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Integration tests for account query endpoints.
 *
 * <p>Uses the high-level client by default (inherited from
 * {@link AbstractIntegrationTest}).
 * Low-level tests override the client type via {@link AccountQueryLowLevelIntegrationTest}.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class AccountQueryIntegrationTest extends AbstractIntegrationTest {

    @Test
    void getAccount_whenSeededAccount_shouldReturnBalances() throws Exception {
        performAsync(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc_usd_1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.currentBalance").value(10000))
                .andExpect(jsonPath("$.availableBalance").value(10000))
                .andExpect(jsonPath("$.reservations").isArray())
                .andExpect(jsonPath("$.reservations").isEmpty());
    }

    @Test
    void getAccount_whenAccountMissing_shouldReturn404() throws Exception {
        performAsync(get("/api/v1/accounts/acc_nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void getAccount_whenAccountIdMalformed_shouldReturn400ValidationError() throws Exception {
        performAsync(get("/api/v1/accounts/acc$bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getAccount_whenAccountIdTooLong_shouldReturn400ValidationError() throws Exception {
        performAsync(get("/api/v1/accounts/" + "a".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getAccount_whenPaymentCompleted_shouldReflectDebitAndConsumedReservation() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100", "Integration Test");
        processPayment(paymentId);

        performAsync(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc_usd_1"))
                .andExpect(jsonPath("$.currentBalance").value(9900))
                .andExpect(jsonPath("$.availableBalance").value(9900))
                .andExpect(jsonPath("$.reservations[0].paymentId").value(paymentId))
                .andExpect(jsonPath("$.reservations[0].amount").value(100))
                .andExpect(jsonPath("$.reservations[0].status").value("CONSUMED"));
    }

    @Test
    void getAccount_whenPaymentRejected_shouldLeaveBalanceUnchanged() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999", "Integration Test");
        processPayment(paymentId);

        performAsync(get("/api/v1/accounts/acc_eur_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc_eur_1"))
                .andExpect(jsonPath("$.currentBalance").value(10000))
                .andExpect(jsonPath("$.availableBalance").value(10000));
    }

    @Test
    void getAccount_whenPaymentCreatedBeforeProcess_shouldReturnEmptyReservations() throws Exception {
        createPayment("acc_usd_1", "50", "Integration Test");

        performAsync(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(10000))
                .andExpect(jsonPath("$.availableBalance").value(10000))
                .andExpect(jsonPath("$.reservations").isEmpty());
    }

    @Test
    void batchGetReservations_whenPaymentCompleted_shouldReturnReservation() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100", "Integration Test");
        processPayment(paymentId);
        String reservationId = reservationIdForPayment(paymentId);

        performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[\"" + reservationId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationId").value(reservationId))
                .andExpect(jsonPath("$.reservations[0].paymentId").value(paymentId))
                .andExpect(jsonPath("$.reservations[0].amount").value(100))
                .andExpect(jsonPath("$.reservations[0].status").value("CONSUMED"))
                .andExpect(jsonPath("$.missingReservationIds").isEmpty());
    }

    @Test
    void batchGetReservations_whenSomeIdsMissing_shouldListMissingIds() throws Exception {
        List<String> paymentIds = List.of(
                createPayment("acc_usd_1", "10", "Integration Test"),
                createPayment("acc_usd_1", "20", "Integration Test"),
                createPayment("acc_usd_1", "30", "Integration Test"),
                createPayment("acc_usd_1", "40", "Integration Test"),
                createPayment("acc_usd_1", "50", "Integration Test"),
                createPayment("acc_usd_1", "60", "Integration Test"),
                createPayment("acc_usd_1", "70", "Integration Test"));
        for (String paymentId : paymentIds) {
            processPayment(paymentId);
        }
        List<String> reservationIds = new ArrayList<>();
        for (String paymentId : paymentIds) {
            reservationIds.add(reservationIdForPayment(paymentId));
        }
        reservationIds.add("res_fake");
        reservationIds.add("res_fake_2");

        ResultActions result = performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(reservationIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(7))
                .andExpect(jsonPath("$.missingReservationIds.length()").value(2))
                .andExpect(jsonPath("$.missingReservationIds[0]").value("res_fake"))
                .andExpect(jsonPath("$.missingReservationIds[1]").value("res_fake_2"));

        List<Integer> amounts = List.of(10, 20, 30, 40, 50, 60, 70);
        List<String> statuses = List.of(
                "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED");
        assertReservations(
                result,
                reservationIds.subList(0, 7),
                paymentIds,
                amounts,
                statuses);
    }

    @Test
    void batchGetReservations_whenSevenConsumedReservations_shouldPreserveRequestedOrder() throws Exception {
        List<String> paymentIds = List.of(
                createPayment("acc_usd_1", "10", "Integration Test"),
                createPayment("acc_usd_1", "20", "Integration Test"),
                createPayment("acc_usd_1", "30", "Integration Test"),
                createPayment("acc_usd_1", "40", "Integration Test"),
                createPayment("acc_usd_1", "50", "Integration Test"),
                createPayment("acc_usd_1", "60", "Integration Test"),
                createPayment("acc_usd_1", "70", "Integration Test"));
        for (String paymentId : paymentIds) {
            processPayment(paymentId);
        }

        List<String> reservationIds = List.of(
                reservationIdForPayment(paymentIds.get(6)),
                reservationIdForPayment(paymentIds.get(4)),
                reservationIdForPayment(paymentIds.get(2)),
                reservationIdForPayment(paymentIds.get(0)),
                reservationIdForPayment(paymentIds.get(1)),
                reservationIdForPayment(paymentIds.get(3)),
                reservationIdForPayment(paymentIds.get(5)));
        List<String> orderedPaymentIds = List.of(
                paymentIds.get(6), paymentIds.get(4), paymentIds.get(2), paymentIds.get(0),
                paymentIds.get(1), paymentIds.get(3), paymentIds.get(5));
        List<Integer> amounts = List.of(70, 50, 30, 10, 20, 40, 60);
        List<String> statuses = List.of(
                "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED");

        ResultActions result = performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(reservationIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(7))
                .andExpect(jsonPath("$.missingReservationIds").isEmpty());

        assertReservations(result, reservationIds, orderedPaymentIds, amounts, statuses);
    }

    @Test
    void batchGetReservations_whenMixedActiveAndConsumedIds_shouldPreserveRequestedOrder() throws Exception {
        List<String> consumedPaymentIds = List.of(
                createPayment("acc_usd_1", "101", "Integration Test"),
                createPayment("acc_usd_1", "102", "Integration Test"),
                createPayment("acc_usd_1", "103", "Integration Test"),
                createPayment("acc_usd_1", "104", "Integration Test"));
        for (String paymentId : consumedPaymentIds) {
            processPayment(paymentId);
        }

        seedReservation("acc_usd_1", "res_active_1", "pay_active_1", "15", "ACTIVE");
        seedReservation("acc_usd_1", "res_active_2", "pay_active_2", "25", "ACTIVE");
        seedReservation("acc_usd_1", "res_active_3", "pay_active_3", "35", "ACTIVE");

        String r0 = reservationIdForPayment(consumedPaymentIds.get(0));
        String r1 = reservationIdForPayment(consumedPaymentIds.get(1));
        String r2 = reservationIdForPayment(consumedPaymentIds.get(2));
        String r3 = reservationIdForPayment(consumedPaymentIds.get(3));

        List<String> reservationIds = List.of(
                "res_active_2",
                r3,
                r0,
                "res_active_1",
                r2,
                r1,
                "res_active_3");
        List<String> paymentIds = List.of(
                "pay_active_2",
                consumedPaymentIds.get(3),
                consumedPaymentIds.get(0),
                "pay_active_1",
                consumedPaymentIds.get(2),
                consumedPaymentIds.get(1),
                "pay_active_3");
        List<Integer> amounts = List.of(25, 104, 101, 15, 103, 102, 35);
        List<String> statuses = List.of(
                "ACTIVE", "CONSUMED", "CONSUMED", "ACTIVE", "CONSUMED", "CONSUMED", "ACTIVE");

        ResultActions result = performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(reservationIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(7))
                .andExpect(jsonPath("$.missingReservationIds").isEmpty());

        assertReservations(result, reservationIds, paymentIds, amounts, statuses);
    }

    @Test
    void batchGetReservations_whenRepeatedIdsProvided_shouldDeduplicateAndKeepMissingIdsInOrder() throws Exception {
        List<String> paymentIds = List.of(
                createPayment("acc_usd_1", "11", "Integration Test"),
                createPayment("acc_usd_1", "12", "Integration Test"),
                createPayment("acc_usd_1", "13", "Integration Test"),
                createPayment("acc_usd_1", "14", "Integration Test"),
                createPayment("acc_usd_1", "15", "Integration Test"),
                createPayment("acc_usd_1", "16", "Integration Test"),
                createPayment("acc_usd_1", "17", "Integration Test"));
        for (String paymentId : paymentIds) {
            processPayment(paymentId);
        }

        String r0 = reservationIdForPayment(paymentIds.get(0));
        String r1 = reservationIdForPayment(paymentIds.get(1));
        String r2 = reservationIdForPayment(paymentIds.get(2));
        String r3 = reservationIdForPayment(paymentIds.get(3));
        String r4 = reservationIdForPayment(paymentIds.get(4));
        String r5 = reservationIdForPayment(paymentIds.get(5));
        String r6 = reservationIdForPayment(paymentIds.get(6));

        List<String> requestIds = List.of(
                r6, r4, r4, r2, r2, "res_missing", r0, r1, r1, r3, r5, r5);

        ResultActions result = performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(requestIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(7))
                .andExpect(jsonPath("$.missingReservationIds.length()").value(1))
                .andExpect(jsonPath("$.missingReservationIds[0]").value("res_missing"));

        List<String> foundReservationIds = List.of(r6, r4, r2, r0, r1, r3, r5);
        List<String> foundPaymentIds = List.of(
                paymentIds.get(6),
                paymentIds.get(4),
                paymentIds.get(2),
                paymentIds.get(0),
                paymentIds.get(1),
                paymentIds.get(3),
                paymentIds.get(5));
        List<Integer> amounts = List.of(17, 15, 13, 11, 12, 14, 16);
        List<String> statuses = List.of(
                "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED");
        assertReservations(result, foundReservationIds, foundPaymentIds, amounts, statuses);
    }

    @Test
    void batchGetReservations_whenReservationIdsEmpty_shouldReturn400ValidationError() throws Exception {
        performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_whenReservationIdBlank_shouldReturn400ValidationError() throws Exception {
        performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[\"res_ok\",\"  \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_whenMoreThan100Ids_shouldReturn400ValidationError() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add("res_" + i);
        }

        performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(ids)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_whenAllIdsMissing_shouldReturnEmptyReservations() throws Exception {
        List<String> missingIds = List.of(
                "res_nonexistent_1",
                "res_nonexistent_2",
                "res_nonexistent_3",
                "res_nonexistent_4",
                "res_nonexistent_5",
                "res_nonexistent_6",
                "res_nonexistent_7");

        performAsync(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(missingIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations").isEmpty())
                .andExpect(jsonPath("$.missingReservationIds.length()").value(7));
    }

    @Test
    void getAccount_whenMultiplePaymentsExist_shouldShowAllReservations() throws Exception {
        String paymentId1 = createPayment("acc_usd_1", "100", "Integration Test");
        processPayment(paymentId1);

        String paymentId2 = createPayment("acc_usd_1", "200", "Integration Test");
        processPayment(paymentId2);

        performAsync(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBalance").value(9700))
                .andExpect(jsonPath("$.availableBalance").value(9700))
                .andExpect(jsonPath("$.reservations.length()").value(2));
    }
}
