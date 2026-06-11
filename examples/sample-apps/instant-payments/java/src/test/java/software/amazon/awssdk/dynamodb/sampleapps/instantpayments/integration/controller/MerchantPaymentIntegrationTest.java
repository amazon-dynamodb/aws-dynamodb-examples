package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;

/**
 * Integration tests for merchant payment list queries.
 *
 * <p>{@code GET /api/v1/merchants/{merchantId}/payments}: list payments by merchant via
 * {@code GSI_MERCHANT_PAYMENTS}, newest first.
 *
 * <p>{@code GET /api/v1/merchants/{merchantId}/payments/state/{state}}: list payments filtered by
 * merchant and state via {@code GSI_MERCHANT_STATE_PAYMENTS}.
 *
 * <p>Uses the high-level client by default (inherited from
 * {@link AbstractIntegrationTest}).
 * Low-level tests override the client type via {@link MerchantPaymentLowLevelIntegrationTest}.
 *
 * <p>Assertions poll the read model instead of fixed sleeps: GSIs are eventually consistent and
 * state-changing stream processing runs on a 1s tick ({@code DynamoDbStreamsPaymentEventListener}),
 * so a 500ms sleep is often shorter than one poll cycle and causes intermittent failures.
 */
@Tag("integration")
public class MerchantPaymentIntegrationTest extends AbstractIntegrationTest {

    private static final Duration READ_MODEL_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration READ_MODEL_POLL = Duration.ofMillis(200);

    @Test
    void listMerchantPayments_whenMultipleMerchantsExist_shouldReturnOnlyRequestedMerchant() throws Exception {
        String merchant1 = "merch_" + UUID.randomUUID();
        String merchant2 = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchant1, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchant1, "acc_usd_1", "20");
        createPayment(UUID.randomUUID().toString(), merchant2, "acc_usd_1", "30");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchant1 + "/payments"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.items[0].merchantId").value(merchant1))
                        .andExpect(jsonPath("$.items[1].merchantId").value(merchant1)));
    }

    @Test
    void listMerchantPayments_whenMerchantIdMalformed_shouldReturn400ValidationError() throws Exception {
        performAsync(get("/api/v1/merchants/merch$bad/payments"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void listMerchantPaymentsByState_whenStateMalformed_shouldReturn400ValidationError() throws Exception {
        performAsync(get("/api/v1/merchants/merch_1/payments/state/COMPLETED$bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void listMerchantPayments_whenMultiplePaymentsExist_shouldReturnNewestFirst() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult result = performAsync(get("/api/v1/merchants/" + merchantId + "/payments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andReturn();

            String json = result.getResponse().getContentAsString();
            Instant first = Instant.parse(JsonPathSupport.read(json, "$.items[0].createdAtUtc"));
            Instant second = Instant.parse(JsonPathSupport.read(json, "$.items[1].createdAtUtc"));

            assertThat(first.isAfter(second) || first.equals(second))
                    .as("Expected newest first but got %s before %s", first, second)
                    .isTrue();
        });
    }

    @Test
    void listMerchantPayments_whenScanIndexForwardTrue_shouldReturnOldestFirst() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult result = performAsync(
                            get("/api/v1/merchants/" + merchantId + "/payments?scanIndexForward=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andReturn();

            String json = result.getResponse().getContentAsString();
            Instant first = Instant.parse(JsonPathSupport.read(json, "$.items[0].createdAtUtc"));
            Instant second = Instant.parse(JsonPathSupport.read(json, "$.items[1].createdAtUtc"));

            assertThat(first.isBefore(second) || first.equals(second))
                    .as("Expected oldest first but got %s before %s", first, second)
                    .isTrue();
        });
    }

    @Test
    void listMerchantPayments_whenLimitProvided_shouldRespectLimit() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "30");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments?limit=2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.nextToken").isString()));
    }

    @Test
    void listMerchantPayments_whenUsingNextToken_shouldPaginate() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "30");

        String firstPageJson = awaitFirstMerchantPageWithNextToken(merchantId);
        String firstPaymentId = JsonPathSupport.read(firstPageJson, "$.items[0].paymentId");
        String nextToken = JsonPathSupport.read(firstPageJson, "$.nextToken");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult secondPage = performAsync(get(
                            "/api/v1/merchants/" + merchantId + "/payments?limit=1&nextToken=" + nextToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn();

            String secondJson = secondPage.getResponse().getContentAsString();
            String secondPaymentId = JsonPathSupport.read(secondJson, "$.items[0].paymentId");
            assertThat(secondPaymentId).isNotEqualTo(firstPaymentId);
        });
    }

    @Test
    void listMerchantPayments_whenNextTokenInvalid_shouldReturn400() throws Exception {
        performAsync(get("/api/v1/merchants/merch_1/payments?nextToken=not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPayments_whenStateRouteNextTokenOnMerchantRoute_shouldReturn400() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");

        String firstPageJson = awaitFirstMerchantStatePageWithNextToken(merchantId, "RECEIVED");
        String wrongRouteToken = JsonPathSupport.read(firstPageJson, "$.nextToken");

        performAsync(get("/api/v1/merchants/" + merchantId + "/payments?limit=1&nextToken=" + wrongRouteToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPaymentsByState_whenMerchantRouteNextTokenOnStateRoute_shouldReturn400() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments?limit=1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.nextToken").isString()));

        String firstPageJson = awaitFirstMerchantPageWithNextToken(merchantId);
        String wrongRouteToken = JsonPathSupport.read(firstPageJson, "$.nextToken");

        performAsync(get(
                        "/api/v1/merchants/" + merchantId + "/payments/state/RECEIVED?limit=1&nextToken="
                                + wrongRouteToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPayments_whenTokenFromAnotherMerchant_shouldReturn400() throws Exception {
        String merchantA = "merch_" + UUID.randomUUID();
        String merchantB = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantA, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantA, "acc_usd_1", "20");

        String firstPageJson = awaitFirstMerchantPageWithNextToken(merchantA);
        String tokenForMerchantA = JsonPathSupport.read(firstPageJson, "$.nextToken");

        // Replaying merchant A's token on merchant B's route is a 400, not a 500 from a rejected start key.
        performAsync(get(
                        "/api/v1/merchants/" + merchantB + "/payments?limit=1&nextToken=" + tokenForMerchantA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPaymentsByState_whenTokenFromAnotherMerchant_shouldReturn400() throws Exception {
        String merchantA = "merch_" + UUID.randomUUID();
        String merchantB = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantA, "acc_usd_1", "10");
        createPayment(UUID.randomUUID().toString(), merchantA, "acc_usd_1", "20");

        String firstPageJson = awaitFirstMerchantStatePageWithNextToken(merchantA, "RECEIVED");
        String tokenForMerchantA = JsonPathSupport.read(firstPageJson, "$.nextToken");

        performAsync(get(
                        "/api/v1/merchants/" + merchantB + "/payments/state/RECEIVED?limit=1&nextToken="
                                + tokenForMerchantA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPayments_whenLimitOmitted_shouldUseDefaultLimit() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1)));
    }

    @Test
    void listMerchantPayments_whenNoPaymentsExist_shouldReturnEmptyArray() throws Exception {
        performAsync(get("/api/v1/merchants/merch_nonexistent/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    void listMerchantPaymentsByState_whenStateMatches_shouldFilterCorrectly() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        MvcResult created1 = createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        String paymentId1 = JsonPathSupport.read(created1.getResponse().getContentAsString(), "$.paymentId");
        String correlationId1 = JsonPathSupport.read(created1.getResponse().getContentAsString(), "$.correlationId");
        processPayment(paymentId1);

        // Amount far exceeds seeded balance so async stream processing rejects instead of completing.
        // Otherwise both payments would end up COMPLETED and this filter assertion would be wrong.
        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "999999");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments/state/COMPLETED"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].paymentId").value(paymentId1))
                        .andExpect(jsonPath("$.items[0].state").value("COMPLETED"))
                        .andExpect(jsonPath("$.items[0].merchantId").value(merchantId))
                        .andExpect(jsonPath("$.items[0].correlationId").value(correlationId1))
                        .andExpect(jsonPath("$.items[0].amount").value(10))
                        .andExpect(jsonPath("$.items[0].currency").value("USD"))
                        .andExpect(jsonPath("$.items[0].version").value(3))
                        .andExpect(jsonPath("$.items[0].createdAtUtc").isString())
                        .andExpect(jsonPath("$.items[0].updatedAtUtc").isString()));
    }

    @Test
    void listMerchantPaymentsByState_whenScanIndexForwardTrue_shouldReturnOldestFirst() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        MvcResult created1 = createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        String paymentId1 = JsonPathSupport.read(created1.getResponse().getContentAsString(), "$.paymentId");
        processPayment(paymentId1);

        MvcResult created2 = createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20");
        String paymentId2 = JsonPathSupport.read(created2.getResponse().getContentAsString(), "$.paymentId");
        processPayment(paymentId2);

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments/state/COMPLETED"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2)));

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult result = performAsync(get(
                            "/api/v1/merchants/" + merchantId
                                    + "/payments/state/COMPLETED?scanIndexForward=true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(2))
                    .andReturn();

            String json = result.getResponse().getContentAsString();
            Instant first = Instant.parse(JsonPathSupport.read(json, "$.items[0].createdAtUtc"));
            Instant second = Instant.parse(JsonPathSupport.read(json, "$.items[1].createdAtUtc"));

            assertThat(first.isBefore(second) || first.equals(second))
                    .as("Expected oldest first but got %s before %s", first, second)
                    .isTrue();
        });
    }

    @Test
    void listMerchantPaymentsByState_whenUsingNextToken_shouldPaginate() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        String paymentId1 = JsonPathSupport.read(
                createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10")
                        .getResponse().getContentAsString(), "$.paymentId");
        processPayment(paymentId1);

        String paymentId2 = JsonPathSupport.read(
                createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "20")
                        .getResponse().getContentAsString(), "$.paymentId");
        processPayment(paymentId2);

        String firstPageJson = awaitFirstMerchantStatePageWithNextToken(merchantId, "COMPLETED");
        String firstPaymentId = JsonPathSupport.read(firstPageJson, "$.items[0].paymentId");
        String nextToken = JsonPathSupport.read(firstPageJson, "$.nextToken");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult secondPage = performAsync(get(
                            "/api/v1/merchants/" + merchantId
                                    + "/payments/state/COMPLETED?limit=1&nextToken=" + nextToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andReturn();

            String secondJson = secondPage.getResponse().getContentAsString();
            String secondPaymentId = JsonPathSupport.read(secondJson, "$.items[0].paymentId");
            assertThat(secondPaymentId).isNotEqualTo(firstPaymentId);
            assertThat(List.of(paymentId1, paymentId2)).contains(secondPaymentId);
        });
    }

    @Test
    void listMerchantPaymentsByState_whenNextTokenInvalid_shouldReturn400() throws Exception {
        performAsync(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?nextToken=not-base64"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPaymentsByState_whenStateCaseDiffers_shouldMatchCaseInsensitively() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        MvcResult created = createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");
        String paymentId = JsonPathSupport.read(created.getResponse().getContentAsString(), "$.paymentId");
        processPayment(paymentId);

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments/state/completed"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].paymentId").value(paymentId)));
    }

    @Test
    void listMerchantPaymentsByState_whenStateInvalid_shouldReturn400() throws Exception {
        performAsync(get("/api/v1/merchants/merch_1/payments/state/BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYMENT_STATE"));
    }

    @Test
    void listMerchantPaymentsByState_whenNoMatchesExist_shouldReturnEmptyArray() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        createPayment(UUID.randomUUID().toString(), merchantId, "acc_usd_1", "10");

        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() ->
                performAsync(get("/api/v1/merchants/" + merchantId + "/payments/state/FUNDS_RESERVED"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items").isArray())
                        .andExpect(jsonPath("$.items").isEmpty())
                        .andExpect(jsonPath("$.nextToken").doesNotExist()));
    }

    /**
     * Polls until the merchant payments list returns a first page with a {@code nextToken}.
     *
     * @param merchantId merchant id for the list route
     * @return JSON body of the first page that includes {@code nextToken}
     */
    private String awaitFirstMerchantPageWithNextToken(String merchantId) {
        final String[] firstPageJson = new String[1];
        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult result = performAsync(get("/api/v1/merchants/" + merchantId + "/payments?limit=1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.nextToken").isString())
                    .andReturn();
            firstPageJson[0] = result.getResponse().getContentAsString();
        });
        return firstPageJson[0];
    }

    /**
     * Polls until the merchant state list returns a first page with a {@code nextToken}.
     *
     * @param merchantId merchant id for the state list route
     * @param state aggregate state filter for the route
     * @return JSON body of the first page that includes {@code nextToken}
     */
    private String awaitFirstMerchantStatePageWithNextToken(String merchantId, String state) {
        final String[] firstPageJson = new String[1];
        await().atMost(READ_MODEL_TIMEOUT).pollInterval(READ_MODEL_POLL).untilAsserted(() -> {
            MvcResult result = performAsync(get(
                            "/api/v1/merchants/" + merchantId + "/payments/state/" + state + "?limit=1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.nextToken").isString())
                    .andReturn();
            firstPageJson[0] = result.getResponse().getContentAsString();
        });
        return firstPageJson[0];
    }

    /**
     * Creates an outbound payment for merchant list tests and returns the MockMvc result.
     *
     * @param idempotencyKey idempotency key for the create request
     * @param merchantId merchant id in the request body
     * @param accountId debtor account id in the request body
     * @param amount payment amount as a numeric string
     * @return MockMvc result from a successful create response
     * @throws Exception when the HTTP request fails or returns a non-201 status
     */
    private MvcResult createPayment(String idempotencyKey, String merchantId, String accountId,
                                    String amount) throws Exception {
        String json = """
                {
                    "idempotencyKey": "%s",
                    "merchantId": "%s",
                    "debtorAccountId": "%s",
                    "creditorIban": "RO49AAAA1B31007593840000",
                    "creditorName": "John Doe",
                    "amount": %s,
                    "currency": "USD"
                }
                """.formatted(idempotencyKey, merchantId, accountId, amount);
        return performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
    }
}
