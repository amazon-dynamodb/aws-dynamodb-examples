package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * Integration tests for outbound payment processing
 * (validate → reserve → complete/reject) and idempotent state transitions.
 *
 * <p>Duplicate-processing scenarios verify that retries do not corrupt state,
 * create extra ledger entries, or debit the account more than once.
 *
 * <p>Uses the high-level client by default (inherited from
 * {@link AbstractIntegrationTest}).
 * Low-level tests override the client type via
 * {@link PaymentProcessingLowLevelIntegrationTest}.
 */
@Tag("integration")
public class PaymentProcessingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void processPayment_whenCreatedAndProcessed_shouldCompletePayment() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.reasonCode").isEmpty());

        assertPaymentState(paymentId, "COMPLETED");
        assertAccountBalanceDecremented("acc_usd_1");
        assertReservationConsumed(paymentId, "acc_usd_1");
        assertLedgerEntryExists(paymentId, "acc_usd_1");
    }

    @Test
    void getOutboundPayment_whenPaymentIdMalformed_shouldReturn400ValidationError() throws Exception {
        performAsync(get("/api/v1/payments/outbound/pay$bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void processPayment_whenPaymentIdTooLong_shouldReturn400ValidationError() throws Exception {
        performAsync(post("/api/v1/payments/outbound/" + "a".repeat(65) + "/process"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void processPayment_whenCreatedAndProcessedWithHighLevelClient_shouldIncrementAccountVersionExactlyTwice() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        Map<String, AttributeValue> account = getAccount("acc_usd_1");
        assertThat(Integer.parseInt(account.get("version").n()))
                .as("seeded version 1 should advance once on reserve and once on complete")
                .isEqualTo(3);
    }

    @Test
    void processPayment_whenInsufficientFunds_shouldReject() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("INSUFFICIENT_FUNDS"));

        assertPaymentState(paymentId, "REJECTED");
    }

    @Test
    void processPayment_whenInsufficientFundsWithHighLevelClient_shouldNotIncrementAccountVersion() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("INSUFFICIENT_FUNDS"));

        Map<String, AttributeValue> account = getAccount("acc_eur_1");
        assertThat(Integer.parseInt(account.get("version").n()))
                .as("validation rejection should not mutate the account row")
                .isEqualTo(1);
    }

    @Test
    void processPayment_whenPaymentMissing_shouldReturn404() throws Exception {
        performAsync(post("/api/v1/payments/outbound/pay_nonexistent/process"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getOutboundPayment_whenPaymentMissing_shouldReturn404() throws Exception {
        performAsync(get("/api/v1/payments/outbound/pay_no_such_id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getOutboundPayment_whenPaymentCreated_shouldReturnAggregateConsistentWithStateAndStream() throws Exception {
        String paymentId = createPayment("acc_usd_1", "10");

        MvcResult result = performAsync(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.debtorAccountId").value("acc_usd_1"))
                .andReturn();

        String json = result.getResponse().getContentAsString();
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

    @Test
    void getOutboundPayment_whenPaymentProcessed_shouldReturnCompletedAndHistory() throws Exception {
        String paymentId = createPayment("acc_usd_4", "15");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        performAsync(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.reasonCode").isEmpty())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].eventType").value("OUTBOUND_PAYMENT_CREATED"))
                .andExpect(jsonPath("$.events[1].eventType").value("FUNDS_RESERVED"))
                .andExpect(jsonPath("$.events[2].eventType").value("COMPLETED"));

        Map<String, AttributeValue> head = getStreamHeadItem(paymentId);
        assertThat(Integer.parseInt(head.get("lastSequence").n())).isEqualTo(3);
        assertThat(head.get("aggregateState").s()).isEqualTo("COMPLETED");
    }

    @Test
    void processPayment_whenAlreadyCompleted_shouldBeIdempotent() throws Exception {
        String paymentId = createPayment("acc_usd_2", "50");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        Map<String, AttributeValue> accountAfterFirst = getAccount("acc_usd_2");
        BigDecimal currentAfterFirst = new BigDecimal(accountAfterFirst.get("currentBalance").n());
        BigDecimal availableAfterFirst = new BigDecimal(accountAfterFirst.get("availableBalance").n());
        int versionAfterFirst = Integer.parseInt(accountAfterFirst.get("version").n());

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        assertPaymentState(paymentId, "COMPLETED");
        assertReservationConsumed(paymentId, "acc_usd_2");
        assertSingleLedgerEntry(paymentId);

        Map<String, AttributeValue> accountAfterSecond = getAccount("acc_usd_2");
        assertThat(new BigDecimal(accountAfterSecond.get("currentBalance").n()))
                .as("currentBalance must not change on duplicate processing")
                .isEqualByComparingTo(currentAfterFirst);
        assertThat(new BigDecimal(accountAfterSecond.get("availableBalance").n()))
                .as("availableBalance must not change on duplicate processing")
                .isEqualByComparingTo(availableAfterFirst);
        assertThat(Integer.parseInt(accountAfterSecond.get("version").n()))
                .as("account version must not change on duplicate processing")
                .isEqualTo(versionAfterFirst);
    }

    @Test
    void processPayment_whenAlreadyRejected_shouldBeIdempotent() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        int seqAfterFirst = Integer.parseInt(
                getStreamHeadItem(paymentId).get("lastSequence").n());

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        Map<String, AttributeValue> headAfterSecond = getStreamHeadItem(paymentId);
        assertThat(headAfterSecond.get("aggregateState").s()).isEqualTo("REJECTED");
        assertThat(Integer.parseInt(headAfterSecond.get("lastSequence").n()))
                .as("stream sequence must not change on duplicate rejection")
                .isEqualTo(seqAfterFirst);
    }

    @Test
    void processPayment_whenProcessedTwiceFromReceived_shouldCompleteOnceWithCorrectBalances() throws Exception {
        String paymentId = createPayment("acc_usd_3", "100");

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        performAsync(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        assertPaymentState(paymentId, "COMPLETED");
        assertReservationConsumed(paymentId, "acc_usd_3");
        assertSingleLedgerEntry(paymentId);

        Map<String, AttributeValue> account = getAccount("acc_usd_3");
        assertThat(new BigDecimal(account.get("currentBalance").n()))
                .as("currentBalance should reflect exactly one debit of 100 from initial 500")
                .isEqualByComparingTo(new BigDecimal("400"));
        assertThat(new BigDecimal(account.get("availableBalance").n()))
                .as("availableBalance should reflect exactly one debit of 100 from initial 500")
                .isEqualByComparingTo(new BigDecimal("400"));
    }

    /**
     * Creates an outbound payment via REST and returns its id.
     *
     * @param debtorAccountId seeded debtor account id
     * @param amount payment amount as a numeric string
     * @return created payment id from the response body
     * @throws Exception when the HTTP request fails or returns a non-201 status
     */
    private String createPayment(String debtorAccountId, String amount) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "%s",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Integration Test",
                  "amount": %s,
                  "currency": "USD"
                }""".formatted(idempotencyKey, debtorAccountId, amount);

        MvcResult result = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPathSupport.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

    /**
     * Asserts the payment stream head row has the expected aggregate state.
     *
     * @param paymentId payment id to load
     * @param expectedState expected {@code aggregateState} value
     */
    private void assertPaymentState(String paymentId, String expectedState) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();

        assertThat(response.hasItem()).isTrue();
        assertThat(response.item().get("aggregateState").s()).isEqualTo(expectedState);
    }

    /**
     * Asserts the account row version increased after a successful debit flow.
     *
     * @param accountId seeded account id without the {@code ACCOUNT#} prefix
     */
    private void assertAccountBalanceDecremented(String accountId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("ACCOUNT#" + accountId).build(),
                        "SK", AttributeValue.builder().s("ACCOUNT#" + accountId).build()))
                .build()).join();

        assertThat(response.hasItem()).isTrue();
        int version = Integer.parseInt(response.item().get("version").n());
        assertThat(version).isGreaterThan(1);
    }

    /**
     * Asserts the reservation linked to a completed payment is marked {@code CONSUMED}.
     *
     * @param paymentId payment id whose reservation id follows {@code res_<paymentId>}
     * @param accountId account partition that owns the reservation
     */
    private void assertReservationConsumed(String paymentId, String accountId) {
        String reservationId = "res_" + paymentId;
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("ACCOUNT#" + accountId).build(),
                        "SK", AttributeValue.builder().s("RESERVATION#" + reservationId).build()))
                .build()).join();

        assertThat(response.hasItem()).isTrue();
        assertThat(response.item().get("status").s()).isEqualTo("CONSUMED");
    }

    /**
     * Asserts at least one debit ledger entry exists for the payment.
     *
     * @param paymentId payment id embedded in the ledger entry id
     * @param accountId expected account partition for the entry
     */
    private void assertLedgerEntryExists(String paymentId, String accountId) {
        ScanResponse scanResult = scanLedgerEntries(paymentId);
        assertThat(scanResult.count()).isGreaterThanOrEqualTo(1);
        var item = scanResult.items().getFirst();
        assertThat(item.get("PK").s()).isEqualTo("ACCOUNT#" + accountId);
        assertThat(item.get("entryType").s()).isEqualTo("DEBIT");
        assertThat(item.get("paymentId").s()).isEqualTo(paymentId);
    }

    /**
     * Asserts exactly one ledger entry exists for the payment.
     *
     * @param paymentId payment id embedded in the ledger entry id
     */
    private void assertSingleLedgerEntry(String paymentId) {
        ScanResponse scanResult = scanLedgerEntries(paymentId);
        assertThat(scanResult.count())
                .as("exactly one ledger entry must exist for payment %s", paymentId)
                .isEqualTo(1);
    }

    /**
     * Scans the table for ledger rows matching the derived entry id for a payment.
     *
     * @param paymentId payment id used to build {@code led_<paymentId>}
     * @return scan response containing matching ledger items
     */
    private ScanResponse scanLedgerEntries(String paymentId) {
        String ledgerEntryId = "led_" + paymentId;
        return dynamoDbAsyncClient.scan(r -> r
                .tableName(tableName)
                .filterExpression("ledgerEntryId = :id")
                .expressionAttributeValues(Map.of(
                        ":id", AttributeValue.builder().s(ledgerEntryId).build()))
        ).join();
    }

    /**
     * Loads the account item and fails the test when the row is missing.
     *
     * @param accountId account id without the {@code ACCOUNT#} prefix
     * @return DynamoDB item map for the account row
     */
    private Map<String, AttributeValue> getAccount(String accountId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("ACCOUNT#" + accountId).build(),
                        "SK", AttributeValue.builder().s("ACCOUNT#" + accountId).build()))
                .build()).join();
        assertThat(response.hasItem()).isTrue();
        return response.item();
    }

    /**
     * Loads the payment stream head item and fails the test when the row is missing.
     *
     * @param paymentId payment id without the {@code PAYMENT#} prefix
     * @return DynamoDB item map for the stream head row
     */
    private Map<String, AttributeValue> getStreamHeadItem(String paymentId) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();
        assertThat(response.hasItem()).isTrue();
        return response.item();
    }
}
