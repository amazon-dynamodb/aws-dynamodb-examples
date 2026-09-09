package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * Concurrency integration test: drives two processors on the same payment at once and asserts the
 * invariants hold, a single reservation, a single ledger line, and exactly one debit. Whether the
 * loser hits {@code ConditionalCheckFailed} or the in-call {@code TransactionConflict} retry, the
 * payment converges to a single completed result.
 *
 * <p>The streams poller is disabled here so the only writers are the two explicit calls under test.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class PaymentProcessingConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboundPaymentProcessor processor;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void processPayment_whenTwoProcessorsRaceOnSamePayment_shouldProduceSingleReservationAndLedgerLine() throws Exception {
        String paymentId = createPayment("acc_usd_1", "100", "Concurrency Test");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            Callable<Void> processTask = () -> {
                startGate.await();
                processor.processPayment(paymentId).join();
                return null;
            };

            Future<Void> first = pool.submit(processTask);
            Future<Void> second = pool.submit(processTask);
            startGate.countDown();

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertPaymentState(paymentId, "COMPLETED");
        assertReservationConsumed(paymentId, "acc_usd_1");
        assertSingleLedgerEntry(paymentId);

        Map<String, AttributeValue> account = getAccount("acc_usd_1");
        assertThat(new BigDecimal(account.get("currentBalance").n()))
                .as("exactly one debit of 100 from the seeded 10000")
                .isEqualByComparingTo(new BigDecimal("9900"));
        assertThat(new BigDecimal(account.get("availableBalance").n()))
                .as("exactly one hold-and-debit of 100 from the seeded 10000")
                .isEqualByComparingTo(new BigDecimal("9900"));
        assertThat(Integer.parseInt(account.get("version").n()))
                .as("seeded version 1 advances once on reserve and once on complete")
                .isEqualTo(3);
    }

    /** Asserts the payment stream head row has the expected aggregate state. */
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

    /** Asserts the reservation linked to the completed payment is marked {@code CONSUMED}. */
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

    /** Asserts exactly one ledger entry exists for the payment. */
    private void assertSingleLedgerEntry(String paymentId) {
        String ledgerEntryId = "led_" + paymentId;
        ScanResponse scanResult = dynamoDbAsyncClient.scan(r -> r
                .tableName(tableName)
                .filterExpression("ledgerEntryId = :id")
                .expressionAttributeValues(Map.of(
                        ":id", AttributeValue.builder().s(ledgerEntryId).build()))
        ).join();
        assertThat(scanResult.count())
                .as("exactly one ledger entry must exist for payment %s", paymentId)
                .isEqualTo(1);
    }

    /** Loads the account item and fails the test when the row is missing. */
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
}
