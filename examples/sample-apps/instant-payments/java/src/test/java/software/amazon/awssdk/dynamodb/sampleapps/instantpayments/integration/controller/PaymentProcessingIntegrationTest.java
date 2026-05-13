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
    void createAndProcess_shouldCompletePayment() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
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
    void createAndProcess_highLevelClientShouldIncrementAccountVersionExactlyTwice() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        Map<String, AttributeValue> account = getAccount("acc_usd_1");
        assertThat(Integer.parseInt(account.get("version").n()))
                .as("seeded version 1 should advance once on reserve and once on complete")
                .isEqualTo(3);
    }

    @Test
    void processInsufficientFunds_shouldReject() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.reasonCode").value("INSUFFICIENT_FUNDS"));

        assertPaymentState(paymentId, "REJECTED");
    }

    @Test
    void processInsufficientFunds_highLevelClientShouldNotIncrementAccountVersion() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
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
    void processNonExistentPayment_shouldReturn404() throws Exception {
        mockMvc.perform(post("/api/v1/payments/outbound/pay_nonexistent/process"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getOutboundPayment_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/payments/outbound/pay_no_such_id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getOutboundPayment_afterCreate_returnsAggregate_consistentWithStateAndStream() throws Exception {
        String paymentId = createPayment("acc_usd_1", "10");

        MvcResult result = mockMvc.perform(get("/api/v1/payments/outbound/" + paymentId))
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
    void getOutboundPayment_afterProcess_returnsCompletedAndHistory() throws Exception {
        String paymentId = createPayment("acc_usd_4", "15");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        mockMvc.perform(get("/api/v1/payments/outbound/" + paymentId))
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
    void processAlreadyCompletedPayment_shouldBeIdempotent() throws Exception {
        String paymentId = createPayment("acc_usd_2", "50");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        Map<String, AttributeValue> accountAfterFirst = getAccount("acc_usd_2");
        BigDecimal currentAfterFirst = new BigDecimal(accountAfterFirst.get("currentBalance").n());
        BigDecimal availableAfterFirst = new BigDecimal(accountAfterFirst.get("availableBalance").n());
        int versionAfterFirst = Integer.parseInt(accountAfterFirst.get("version").n());

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
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
    void processAlreadyRejectedPayment_shouldBeIdempotent() throws Exception {
        String paymentId = createPayment("acc_eur_1", "999999");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        int seqAfterFirst = Integer.parseInt(
                getStreamHeadItem(paymentId).get("lastSequence").n());

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REJECTED"));

        Map<String, AttributeValue> headAfterSecond = getStreamHeadItem(paymentId);
        assertThat(headAfterSecond.get("aggregateState").s()).isEqualTo("REJECTED");
        assertThat(Integer.parseInt(headAfterSecond.get("lastSequence").n()))
                .as("stream sequence must not change on duplicate rejection")
                .isEqualTo(seqAfterFirst);
    }

    @Test
    void processTwiceFromReceived_shouldCompleteOnceWithCorrectBalances() throws Exception {
        String paymentId = createPayment("acc_usd_3", "100");

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
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

        MvcResult result = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPathSupport.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

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

    private void assertLedgerEntryExists(String paymentId, String accountId) {
        ScanResponse scanResult = scanLedgerEntries(paymentId);
        assertThat(scanResult.count()).isGreaterThanOrEqualTo(1);
        var item = scanResult.items().getFirst();
        assertThat(item.get("PK").s()).isEqualTo("ACCOUNT#" + accountId);
        assertThat(item.get("entryType").s()).isEqualTo("DEBIT");
        assertThat(item.get("paymentId").s()).isEqualTo(paymentId);
    }

    private void assertSingleLedgerEntry(String paymentId) {
        ScanResponse scanResult = scanLedgerEntries(paymentId);
        assertThat(scanResult.count())
                .as("exactly one ledger entry must exist for payment %s", paymentId)
                .isEqualTo(1);
    }

    private ScanResponse scanLedgerEntries(String paymentId) {
        String ledgerEntryId = "led_" + paymentId;
        return dynamoDbAsyncClient.scan(r -> r
                .tableName(tableName)
                .filterExpression("ledgerEntryId = :id")
                .expressionAttributeValues(Map.of(
                        ":id", AttributeValue.builder().s(ledgerEntryId).build()))
        ).join();
    }

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
