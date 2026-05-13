package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/**
 * Verifies bounded retry / poison-pill behaviour: when {@link OutboundPaymentProcessor#processPayment}
 * always fails, the stream listener exhausts {@link DynamoDbStreamsPaymentEventListener#MAX_PROCESS_RETRIES}
 * attempts and leaves the aggregate in {@code RECEIVED} so the shard can advance.
 */
@Import(DynamoDbStreamsPoisonPillIntegrationTest.PoisonProcessorTestConfig.class)
@Tag("integration")
public class DynamoDbStreamsPoisonPillIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboundPaymentProcessor outboundPaymentProcessor;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void createPayment_streamProcessorAlwaysFails_shouldStayReceivedAfterPoisonPill() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "acc_usd_1",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "Poison pill",
                  "amount": 5,
                  "currency": "USD"
                }""".formatted(idempotencyKey);

        MvcResult createResult = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("RECEIVED"))
                .andReturn();

        String paymentId = JsonPathSupport.read(createResult.getResponse().getContentAsString(), "$.paymentId");

        verify(outboundPaymentProcessor, timeout(25_000).times(DynamoDbStreamsPaymentEventListener.MAX_PROCESS_RETRIES))
                .processPayment(paymentId);

        GetItemResponse row = dynamoDbAsyncClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s("PAYMENT#" + paymentId).build(),
                        "SK", AttributeValue.builder().s(PaymentStreamHead.SORT_KEY).build()))
                .build()).join();

        assertThat(row.hasItem()).isTrue();
        assertThat(row.item().get("aggregateState").s()).isEqualTo("RECEIVED");
    }

    /**
     * Registers a {@link org.springframework.context.annotation.Primary} spy around the real
     * {@link OutboundPaymentProcessor} service bean so stream processing can be forced to fail
     * without replacing the bean type in the context.
     */
    @TestConfiguration
    static class PoisonProcessorTestConfig {

        @Bean
        @Primary
        OutboundPaymentProcessor poisonOutboundPaymentProcessor(
                @Qualifier("outboundPaymentProcessor") OutboundPaymentProcessor real) {
            OutboundPaymentProcessor spy = Mockito.spy(real);
            doThrow(new RuntimeException("simulated stream failure"))
                    .when(spy)
                    .processPayment(anyString());
            return spy;
        }
    }
}
