package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller.MerchantPaymentIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;

/**
 * Smoke tests for merchant payment list endpoints. Each scenario creates real outbound payments for
 * {@code merch_1}, then asserts the merchant APIs return the created payments and usable pagination
 * metadata (not only HTTP 200).
 *
 * <p>For ordering and broader GSI behaviour, see
 * {@link MerchantPaymentIntegrationTest}.
 */
@Tag("smoke")
public class MerchantPaymentSmokeTest extends AbstractIntegrationTest {

    @Test
    void listMerchantPayments_whenPaymentCreated_shouldIncludeCreatedPayment() throws Exception {
        String paymentId = createOutboundPaymentForMerchant("acc_usd_1", 77);

        MvcResult listResult = performAsync(get("/api/v1/merchants/merch_1/payments"))
                .andExpect(status().isOk())
                .andReturn();

        assertListContainsPaymentId(listResult.getResponse().getContentAsString(), paymentId);

        MvcResult oldestFirst = performAsync(
                        get("/api/v1/merchants/merch_1/payments?scanIndexForward=true"))
                .andExpect(status().isOk())
                .andReturn();

        assertListContainsPaymentId(oldestFirst.getResponse().getContentAsString(), paymentId);
    }

    @Test
    void listMerchantPayments_whenMultiplePaymentsExist_shouldSupportPagination() throws Exception {
        createOutboundPaymentForMerchant("acc_usd_1", 11);
        createOutboundPaymentForMerchant("acc_usd_1", 12);

        MvcResult firstPage = performAsync(get("/api/v1/merchants/merch_1/payments?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextToken").isString())
                .andReturn();

        String firstJson = firstPage.getResponse().getContentAsString();
        String firstPaymentId = JsonPathSupport.read(firstJson, "$.items[0].paymentId");
        String nextToken = JsonPathSupport.read(firstJson, "$.nextToken");

        MvcResult secondPage = performAsync(
                        get("/api/v1/merchants/merch_1/payments?limit=1&nextToken=" + nextToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        String secondJson = secondPage.getResponse().getContentAsString();
        String secondPaymentId = JsonPathSupport.read(secondJson, "$.items[0].paymentId");
        assertThat(secondPaymentId).isNotEqualTo(firstPaymentId);
    }

    @Test
    void listMerchantPaymentsByState_whenPaymentCreated_shouldMatchCurrentAggregateState() throws Exception {
        int amountUsd = 88;
        String paymentId = createOutboundPaymentForMerchant("acc_usd_2", amountUsd);

        MvcResult aggregate = performAsync(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andReturn();
        String state = JsonPathSupport.read(aggregate.getResponse().getContentAsString(), "$.state");

        MvcResult listResult = performAsync(get("/api/v1/merchants/merch_1/payments/state/" + state))
                .andExpect(status().isOk())
                .andReturn();

        String listJson = listResult.getResponse().getContentAsString();
        assertListContainsPaymentId(listJson, paymentId);
        assertStateListRowIncludesProjectionFromGsi(listJson, paymentId, amountUsd);
    }

    @Test
    void listMerchantPaymentsByState_whenMultiplePaymentsExist_shouldSupportPagination() throws Exception {
        String paymentId1 = createOutboundPaymentForMerchant("acc_usd_1", 13);
        processPayment(paymentId1);
        String paymentId2 = createOutboundPaymentForMerchant("acc_usd_1", 14);
        processPayment(paymentId2);

        MvcResult firstPage = performAsync(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextToken").isString())
                .andReturn();

        String firstJson = firstPage.getResponse().getContentAsString();
        String firstPaymentId = JsonPathSupport.read(firstJson, "$.items[0].paymentId");
        String nextToken = JsonPathSupport.read(firstJson, "$.nextToken");

        MvcResult secondPage = performAsync(get(
                        "/api/v1/merchants/merch_1/payments/state/COMPLETED?limit=1&nextToken=" + nextToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andReturn();

        String secondJson = secondPage.getResponse().getContentAsString();
        String secondPaymentId = JsonPathSupport.read(secondJson, "$.items[0].paymentId");
        assertThat(List.of(paymentId1, paymentId2)).contains(secondPaymentId);
        assertThat(secondPaymentId).isNotEqualTo(firstPaymentId);
    }

    @Test
    void listMerchantPaymentsByState_whenStateInvalid_shouldReturn400() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments/state/BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYMENT_STATE"))
                .andExpect(jsonPath("$.message").value("Invalid payment state: BOGUS"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void listMerchantPayments_whenNextTokenInvalid_shouldReturn400() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments?nextToken=bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid pagination token"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void listMerchantPaymentsByState_whenNextTokenInvalid_shouldReturn400() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?nextToken=bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid pagination token"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    /**
     * Creates an outbound payment for {@code merch_1} and returns its payment id.
     *
     * @param debtorAccountId seeded debtor account id
     * @param amount payment amount in USD
     * @return created payment id from the response body
     * @throws Exception when the HTTP request fails or returns a non-201 status
     */
    protected String createOutboundPaymentForMerchant(String debtorAccountId, int amount) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "%s",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Merchant smoke",
                  "amount": %d,
                  "currency": "USD"
                }""".formatted(idempotencyKey, debtorAccountId, amount);

        MvcResult create = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String json = create.getResponse().getContentAsString();
        String paymentId = JsonPathSupport.read(json, "$.paymentId");
        JsonPathSupport.assertLogicalPaymentId(paymentId);
        return paymentId;
    }

    /**
     * Drives synchronous payment processing for merchant smoke scenarios.
     *
     * @param paymentId payment id to process
     * @throws Exception when the HTTP request fails or returns a non-200 status
     */
    protected void processPayment(String paymentId) throws Exception {
        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk());
    }

    /**
     * Asserts a merchant list JSON body contains a row with the given payment id.
     *
     * @param listJson merchant list response body
     * @param paymentId payment id expected in {@code $.items}
     */
    private static void assertListContainsPaymentId(String listJson, String paymentId) {
        List<Map<String, Object>> rows = JsonPathSupport.read(listJson, "$.items");
        assertThat(rows).as("merchant list should include paymentId=%s body=%s", paymentId, listJson)
                .filteredOn(row -> paymentId.equals(row.get("paymentId")))
                .isNotEmpty();
    }

    /**
     * Asserts a state-filtered merchant list row includes GSI-projected amount and timestamp fields.
     *
     * @param listJson merchant state list response body
     * @param paymentId payment id of the row to inspect
     * @param amountUsd expected amount from the create request
     */
    private static void assertStateListRowIncludesProjectionFromGsi(String listJson, String paymentId, int amountUsd) {
        List<Map<String, Object>> rows = JsonPathSupport.read(listJson, "$.items");
        Map<String, Object> row = rows.stream()
                .filter(r -> paymentId.equals(r.get("paymentId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing row for paymentId=" + paymentId));
        assertThat(row.get("updatedAtUtc")).as("updatedAtUtc from GSI projection").isNotNull();
        assertThat(Objects.toString(row.get("updatedAtUtc"), "")).isNotBlank();
        assertThat(row.get("amount")).isEqualTo(amountUsd);
        assertThat(row.get("currency")).isEqualTo("USD");
    }
}
