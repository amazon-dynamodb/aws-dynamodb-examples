package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * End-to-end coverage for the DynamoDB Streams entry path: creating a payment via REST without
 * calling {@code /process} should still reach {@code COMPLETED} when the stream listener invokes
 * {@link OutboundPaymentProcessor}.
 *
 * <p>Polls DynamoDB with Awaitility (generous timeout) because the sample stream poller is
 * interval-based.
 *
 * <p>Tagged {@code integration} and {@code smoke} so stream-driven completion is exercised when
 * running either group ({@code -Dgroups=smoke} or {@code -Dgroups=integration}).
 */
@Tag("integration")
@Tag("smoke")
public class DynamoDbStreamsPaymentProcessingIntegrationTest extends AbstractIntegrationTest {

    private static final Duration AWAIT_STATE = Duration.ofSeconds(25);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(400);

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void createPayment_whenManualProcessSkipped_shouldBecomeCompletedViaStream() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_usd_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Streams E2E",
                  "amount": 20,
                  "currency": "USD"
                }""".formatted(idempotencyKey);

        MvcResult createResult = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String paymentId = JsonPathSupport.read(createResult.getResponse().getContentAsString(), "$.paymentId");

        awaitPaymentState(paymentId, "COMPLETED");

        GetItemResponse row = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();

        assertThat(row.hasItem()).isTrue();
        assertThat(row.item().get("aggregateState").s()).isEqualTo("COMPLETED");
    }

    /**
     * Polls until the payment stream head reaches the expected aggregate state.
     *
     * @param paymentId payment id to observe
     * @param expectedState target {@code aggregateState} value
     */
    private void awaitPaymentState(String paymentId, String expectedState) {
        await().atMost(AWAIT_STATE)
                .pollInterval(POLL_INTERVAL)
                .alias("payment %s -> state %s".formatted(paymentId, expectedState))
                .until(() -> aggregateStateEquals(paymentId, expectedState));
    }

    /**
     * Reads the stream head row and compares its aggregate state to the expected value.
     *
     * @param paymentId payment id to load
     * @param expectedState state value to match
     * @return {@code true} when the item exists and {@code aggregateState} equals {@code expectedState}
     */
    private boolean aggregateStateEquals(String paymentId, String expectedState) {
        GetItemResponse response = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();

        if (!response.hasItem()) {
            return false;
        }
        var stateAttr = response.item().get("aggregateState");
        return stateAttr != null && expectedState.equals(stateAttr.s());
    }
}
