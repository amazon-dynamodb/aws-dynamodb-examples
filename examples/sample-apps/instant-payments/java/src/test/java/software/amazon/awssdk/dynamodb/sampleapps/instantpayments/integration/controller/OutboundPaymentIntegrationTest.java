package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Integration tests for outbound payment creation and retrieval endpoints, including idempotency,
 * validation, TTL persistence, and backward-compatible replay of legacy event attributes.
 *
 * <p>DynamoDB Streams are disabled for this class so newly created payments remain in
 * {@code RECEIVED} unless a test explicitly mutates stored data.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class OutboundPaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void createOutboundPayment_whenValidRequest_shouldReturn201AndStoreItems() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = createRequestBody(idempotencyKey, "acc_usd_1", "100");

        MvcResult result = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        String paymentId = JsonPathSupport.read(responseJson, "$.paymentId");
        JsonPathSupport.assertLogicalPaymentId(paymentId);
        JsonPathSupport.assertLogicalCorrelationId(JsonPathSupport.read(responseJson, "$.correlationId"));
        Instant createdAtResponse = JsonPathSupport.readInstantAssertingPlausibleNow(responseJson, "$.createdAtUtc");

        GetItemResponse headItem = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();

        assertThat(headItem.hasItem()).isTrue();
        assertThat(headItem.item().get("aggregateState").s()).isEqualTo("RECEIVED");
        assertThat(headItem.item().get("lastSequence").n()).isEqualTo("1");

        GetItemResponse createdEvent = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentEvent.sortKeyForSequence(1)).build()))
                .build()).join();

        assertThat(createdEvent.hasItem()).isTrue();
        assertThat(createdEvent.item().get("amount").n()).isEqualTo("100");
        assertThat(createdEvent.item().get("debtorAccountId").s()).isEqualTo("acc_usd_1");

        GetItemResponse idempotencyItem = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("IDEMPOTENCY#" + idempotencyKey).build(),
                        "SK", AttributeValue.builder().s("IDEMPOTENCY").build()))
                .build()).join();

        assertThat(idempotencyItem.hasItem()).isTrue();
        assertThat(idempotencyItem.item().get("responseSnapshot").m().get("paymentId").s()).isEqualTo(paymentId);

        AttributeValue createdAtAttr = idempotencyItem.item().get("createdAtUtc");
        assertThat(createdAtAttr.s()).isNotBlank();
        assertThat(Instant.parse(createdAtAttr.s())).isEqualTo(createdAtResponse);
        long ttl = Long.parseLong(idempotencyItem.item().get("ttl").n());
        assertThat(ttl).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(ttl).isEqualTo(createdAtResponse.getEpochSecond() + 2_592_000L);

        DescribeTimeToLiveResponse ttlDesc = dynamoDbAsyncClient
                .describeTimeToLive(DescribeTimeToLiveRequest.builder().tableName(tableName).build())
                .join();
        assertThat(ttlDesc.timeToLiveDescription().timeToLiveStatus()).isEqualTo(TimeToLiveStatus.ENABLED);
        assertThat(ttlDesc.timeToLiveDescription().attributeName()).isEqualTo("ttl");
    }

    @Test
    void getOutboundPayment_whenLegacyTransitionAttributesOnEvent_shouldReplayCorrectly() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = createRequestBody(idempotencyKey, "acc_usd_1", "50");

        MvcResult createResult = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String paymentId = JsonPathSupport.read(createResult.getResponse().getContentAsString(), "$.paymentId");

        dynamoDbAsyncClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentEvent.sortKeyForSequence(1)).build()))
                .updateExpression("SET fromState = :f, toState = :t")
                .expressionAttributeValues(Map.of(
                        ":f", AttributeValue.builder().s("LEGacy_FROM").build(),
                        ":t", AttributeValue.builder().s("LEGacy_TO").build()))
                .build()).join();

        mockMvc.perform(get("/api/v1/payments/outbound/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andExpect(jsonPath("$.events[0].eventType").value("OUTBOUND_PAYMENT_CREATED"));
    }

    @Test
    void createOutboundPayment_whenSameIdempotencyKeyAndPayload_shouldReturn200WithSamePaymentId() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = createRequestBody(idempotencyKey, "acc_usd_2", "200");

        MvcResult firstResult = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        String firstPaymentId = JsonPathSupport.read(firstResult.getResponse().getContentAsString(), "$.paymentId");

        MvcResult retryResult = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String retryPaymentId = JsonPathSupport.read(retryResult.getResponse().getContentAsString(), "$.paymentId");

        assertThat(retryPaymentId).isEqualTo(firstPaymentId);
    }

    @Test
    void createOutboundPayment_whenSameIdempotencyKeyAndDifferentPayload_shouldReturn409() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        String firstRequest = createRequestBody(idempotencyKey, "acc_usd_1", "100");
        mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isCreated());

        String differentRequest = createRequestBody(idempotencyKey, "acc_usd_1", "999");
        mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(differentRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void createOutboundPayment_whenRequiredFieldsMissing_shouldReturn400() throws Exception {
        String requestBody = """
                {"idempotencyKey": "key-1", "merchantId": "merch_1"}""";

        mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    /**
     * Builds a JSON body for {@code POST /api/v1/payments/outbound}.
     *
     * @param idempotencyKey idempotency key for the request
     * @param debtorAccountId debtor account id
     * @param amount payment amount as a numeric string
     * @return formatted JSON request body
     */
    private String createRequestBody(String idempotencyKey, String debtorAccountId, String amount) {
        return """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "%s",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "John Doe",
                  "amount": %s,
                  "currency": "USD"
                }""".formatted(idempotencyKey, debtorAccountId, amount);
    }
}
