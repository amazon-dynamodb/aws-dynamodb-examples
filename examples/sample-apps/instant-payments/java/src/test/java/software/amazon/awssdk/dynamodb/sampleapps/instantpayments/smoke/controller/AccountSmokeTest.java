package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Smoke tests for account query endpoints - verifies the endpoints return balances and
 * metadata that match {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.SeedAccountsData}.
 *
 * <p>Streams are disabled so {@code POST .../process} is the only driver of payment lifecycle here;
 * otherwise the stream listener races manual processing and can surface as HTTP 500 from the
 * process endpoint (see {@link AbstractIntegrationTest}). {@link TestPropertySource} and
 * {@link DynamicPropertySource} both pin {@code dynamodb.streams.enabled=false} so the flag is
 * bound reliably for this context (same idea as low-level tests overriding {@code client-type}).
 */
@Tag("smoke")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class AccountSmokeTest extends AbstractIntegrationTest {

    /**
     * Ensures the DynamoDB Streams poller bean stays off for this class even if test property merging
     * order differs across Spring versions.
     *
     * @param registry test property registry merged after {@link AbstractIntegrationTest} defaults
     */
    @DynamicPropertySource
    static void accountSmokeDisableDynamoDbStreams(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.streams.enabled", () -> "false");
    }

    @Test
    void getAccount_seeded_smokeTest() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc_usd_1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.currentBalance").value(10000))
                .andExpect(jsonPath("$.availableBalance").value(10000))
                .andExpect(jsonPath("$.reservations").isArray())
                .andExpect(jsonPath("$.reservations.length()").value(0));
    }

    @Test
    void batchGetReservations_afterPaymentFlow_smokeTest() throws Exception {
        List<String> paymentIds = List.of(
                createPayment("acc_usd_1", "10", "Smoke Test"),
                createPayment("acc_usd_1", "20", "Smoke Test"),
                createPayment("acc_usd_1", "30", "Smoke Test"),
                createPayment("acc_usd_1", "40", "Smoke Test"),
                createPayment("acc_usd_1", "50", "Smoke Test"),
                createPayment("acc_usd_1", "60", "Smoke Test"),
                createPayment("acc_usd_1", "70", "Smoke Test"));
        for (String paymentId : paymentIds) {
            processPayment(paymentId);
        }

        List<String> reservationIds = List.of(
                reservationIdForPayment(paymentIds.get(1)),
                reservationIdForPayment(paymentIds.get(6)),
                reservationIdForPayment(paymentIds.get(4)),
                reservationIdForPayment(paymentIds.get(0)),
                reservationIdForPayment(paymentIds.get(5)),
                reservationIdForPayment(paymentIds.get(2)),
                reservationIdForPayment(paymentIds.get(3)));
        List<String> orderedPaymentIds = List.of(
                paymentIds.get(1), paymentIds.get(6), paymentIds.get(4), paymentIds.get(0),
                paymentIds.get(5), paymentIds.get(2), paymentIds.get(3));
        List<Integer> amounts = List.of(20, 70, 50, 10, 60, 30, 40);
        List<String> statuses = List.of(
                "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED", "CONSUMED");

        ResultActions result = mockMvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchGetReservationsRequestBody(reservationIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(7))
                .andExpect(jsonPath("$.missingReservationIds").isEmpty());

        assertReservations(result, reservationIds, orderedPaymentIds, amounts, statuses);
    }
}
