package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Smoke tests for outbound payment REST surface, including read-after-create for a single payment.
 */
@Tag("smoke")
public class OutboundPaymentSmokeTest extends AbstractIntegrationTest {

    @Test
    void createOutboundPayment_smokeTest() throws Exception {
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_usd_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Smoke Test",
                  "amount": 50,
                  "currency": "USD"
                }""".formatted(UUID.randomUUID().toString());

        MvcResult created = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String body = created.getResponse().getContentAsString();
        JsonPathSupport.assertLogicalPaymentId(JsonPathSupport.read(body, "$.paymentId"));
        JsonPathSupport.assertLogicalCorrelationId(JsonPathSupport.read(body, "$.correlationId"));
        JsonPathSupport.readInstantAssertingPlausibleNow(body, "$.createdAtUtc");
    }

    /**
     * {@code GET /api/v1/payments/outbound/{paymentId}} returns aggregate + events.
     * Assertions allow DynamoDB Streams to have already advanced the payment past {@code RECEIVED}.
     */
    @Test
    void getOutboundPayment_afterCreate_smokeTest() throws Exception {
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_usd_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Read-after-create smoke",
                  "amount": 11,
                  "currency": "USD"
                }""".formatted(UUID.randomUUID().toString());

        MvcResult create = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId = JsonPathSupport.read(create.getResponse().getContentAsString(), "$.paymentId");

        MvcResult getResult = mockMvc.perform(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andReturn();

        String json = getResult.getResponse().getContentAsString();
        String state = JsonPathSupport.read(json, "$.state");
        int eventCount = JsonPathSupport.arraySize(json, "$.events");

        assertThat(state).isIn("RECEIVED", "FUNDS_RESERVED", "COMPLETED");
        String firstEventType = JsonPathSupport.read(json, "$.events[0].eventType");
        assertThat(firstEventType).isEqualTo("OUTBOUND_PAYMENT_CREATED");
        switch (state) {
            case "RECEIVED" -> assertThat(eventCount).isEqualTo(1);
            case "FUNDS_RESERVED" -> assertThat(eventCount).isEqualTo(2);
            case "COMPLETED" -> assertThat(eventCount).isEqualTo(3);
            default -> throw new AssertionError("unexpected state: " + state);
        }
    }
}
