package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;

/**
 * Smoke test for the end-to-end create → process → verify COMPLETED flow.
 */
@Tag("smoke")
public class PaymentProcessingSmokeTest extends AbstractIntegrationTest {

    @Test
    void processPayment_whenCreatedAndProcessed_shouldComplete() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_usd_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Smoke Test",
                  "amount": 25,
                  "currency": "USD"
                }""".formatted(idempotencyKey);

        MvcResult createResult = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String paymentId = JsonPathSupport.read(createResult.getResponse().getContentAsString(), "$.paymentId");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        performAsync(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.events.length()").value(3));
    }

    @Test
    void processPayment_whenInsufficientFunds_shouldReject() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_eur_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Smoke Test Reject",
                  "amount": 999999,
                  "currency": "EUR"
                }""".formatted(idempotencyKey);

        MvcResult createResult = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String paymentId = JsonPathSupport.read(createResult.getResponse().getContentAsString(), "$.paymentId");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("INSUFFICIENT_FUNDS"));
    }
}
