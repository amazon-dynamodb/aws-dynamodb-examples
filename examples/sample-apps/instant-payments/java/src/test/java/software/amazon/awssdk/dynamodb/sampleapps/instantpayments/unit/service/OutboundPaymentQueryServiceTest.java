package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentQueryService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link OutboundPaymentQueryService}, verifying event-stream replay into API
 * responses and failure on missing or inconsistent payment partitions.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class OutboundPaymentQueryServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Spy
    private final PaymentMapper paymentMapper = new PaymentMapper();

    @Spy
    private PaymentEventReplayer paymentEventReplayer = new PaymentEventReplayer();

    @InjectMocks
    private OutboundPaymentQueryService queryService;

    /** Sets idempotency TTL on the spy mapper so injected configuration matches production defaults. */
    @BeforeEach
    void wirePaymentMapperTtl() {
        ReflectionTestUtils.setField(paymentMapper, "idempotencyTtlSeconds", 2_592_000L);
        ReflectionTestUtils.invokeMethod(paymentMapper, "validateIdempotencyTtlConfiguration");
    }

    @Test
    void getOutboundPayment_whenPartitionMissing_shouldThrowNotFound() {
        when(paymentRepository.queryPaymentPartition(eq("pay_x")))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> queryService.getOutboundPayment("pay_x"))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasFieldOrPropertyWithValue("paymentId", "pay_x");
    }

    @Test
    void getOutboundPayment_whenPartitionExists_shouldMapAggregateAndEvents() {
        Instant created = Instant.parse("2025-06-01T10:00:00Z");

        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey("PAYMENT#pay_m1");
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(1);
        head.setAggregateState(PaymentState.RECEIVED.name());
        head.setUpdatedAtUtc(created);
        head.setPaymentId("pay_m1");
        head.setMerchantId("merch_1");
        head.setCreatedAtUtc(created);
        head.setCorrelationId("corr_1");
        head.setAmount(new BigDecimal("99.50"));
        head.setCurrency("USD");

        PaymentEvent ev = new PaymentEvent();
        ev.setPaymentKey("PAYMENT#pay_m1");
        ev.setEventKey(PaymentEvent.sortKeyForSequence(1));
        ev.setEntityType(PaymentEvent.ENTITY_TYPE);
        ev.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        ev.setSequenceNumber(1);
        ev.setCorrelationId("corr_1");
        ev.setReasonCode(null);
        ev.setOccurredAt(created);
        ev.setPaymentId("pay_m1");
        ev.setMerchantId("merch_1");
        ev.setDebtorAccountId("acc_usd_1");
        ev.setCreditorIban("RO00TEST");
        ev.setCreditorName("ACME");
        ev.setAmount(new BigDecimal("99.50"));
        ev.setCurrency("USD");
        ev.setIdempotencyKey("idem_1");

        when(paymentRepository.queryPaymentPartition(eq("pay_m1")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PaymentPartitionQueryResult(head, List.of(ev))));

        GetOutboundPaymentResponse response = queryService.getOutboundPayment("pay_m1");

        assertThat(response.paymentId()).isEqualTo("pay_m1");
        assertThat(response.state()).isEqualTo("RECEIVED");
        assertThat(response.correlationId()).isEqualTo("corr_1");
        assertThat(response.createdAtUtc()).isEqualTo(created);
        assertThat(response.updatedAtUtc()).isEqualTo(created);
        assertThat(response.debtorAccountId()).isEqualTo("acc_usd_1");
        assertThat(response.creditorIban()).isEqualTo("RO00TEST");
        assertThat(response.creditorName()).isEqualTo("ACME");
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("99.50"));
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.idempotencyKey()).isEqualTo("idem_1");
        assertThat(response.reasonCode()).isNull();
        assertThat(response.version()).isEqualTo(1);

        assertThat(response.events()).hasSize(1);
        assertThat(response.events().getFirst().eventKey()).startsWith("EVENT#");
        assertThat(response.events().getFirst().eventType()).isEqualTo(
                PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        assertThat(response.events().getFirst().reasonCode()).isNull();
        assertThat(response.events().getFirst().correlationId()).isEqualTo("corr_1");
    }

    @Test
    void getOutboundPayment_whenEventsEmpty_shouldThrowFromReplayer() {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey("PAYMENT#pay_empty");
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(1);
        head.setAggregateState(PaymentState.RECEIVED.name());
        head.setUpdatedAtUtc(Instant.now());

        when(paymentRepository.queryPaymentPartition(eq("pay_empty")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PaymentPartitionQueryResult(head, List.of())));

        assertThatThrownBy(() -> queryService.getOutboundPayment("pay_empty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot fold empty event stream");
    }

    @Test
    void getOutboundPayment_whenHeadLastSequenceDisagreesWithFold_shouldThrow() {
        Instant created = Instant.parse("2025-06-01T10:00:00Z");

        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey("PAYMENT#pay_mismatch_seq");
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(99);
        head.setAggregateState(PaymentState.RECEIVED.name());
        head.setUpdatedAtUtc(created);
        head.setPaymentId("pay_mismatch_seq");
        head.setMerchantId("merch_1");
        head.setCreatedAtUtc(created);
        head.setCorrelationId("corr_1");
        head.setAmount(new BigDecimal("99.50"));
        head.setCurrency("USD");

        PaymentEvent ev = new PaymentEvent();
        ev.setPaymentKey("PAYMENT#pay_mismatch_seq");
        ev.setEventKey(PaymentEvent.sortKeyForSequence(1));
        ev.setEntityType(PaymentEvent.ENTITY_TYPE);
        ev.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        ev.setSequenceNumber(1);
        ev.setCorrelationId("corr_1");
        ev.setReasonCode(null);
        ev.setOccurredAt(created);
        ev.setPaymentId("pay_mismatch_seq");
        ev.setMerchantId("merch_1");
        ev.setDebtorAccountId("acc_usd_1");
        ev.setCreditorIban("RO00TEST");
        ev.setCreditorName("ACME");
        ev.setAmount(new BigDecimal("99.50"));
        ev.setCurrency("USD");
        ev.setIdempotencyKey("idem_1");

        when(paymentRepository.queryPaymentPartition(eq("pay_mismatch_seq")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PaymentPartitionQueryResult(head, List.of(ev))));

        assertThatThrownBy(() -> queryService.getOutboundPayment("pay_mismatch_seq"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stream head does not match replayed aggregate");
    }

    @Test
    void getOutboundPayment_whenHeadAggregateStateDisagreesWithFold_shouldThrow() {
        Instant created = Instant.parse("2025-06-01T10:00:00Z");

        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey("PAYMENT#pay_mismatch_state");
        head.setStreamKey(PaymentStreamHead.SORT_KEY);
        head.setEntityType(PaymentStreamHead.ENTITY_TYPE);
        head.setLastSequence(1);
        head.setAggregateState(PaymentState.COMPLETED.name());
        head.setUpdatedAtUtc(created);
        head.setPaymentId("pay_mismatch_state");
        head.setMerchantId("merch_1");
        head.setCreatedAtUtc(created);
        head.setCorrelationId("corr_1");
        head.setAmount(new BigDecimal("99.50"));
        head.setCurrency("USD");

        PaymentEvent ev = new PaymentEvent();
        ev.setPaymentKey("PAYMENT#pay_mismatch_state");
        ev.setEventKey(PaymentEvent.sortKeyForSequence(1));
        ev.setEntityType(PaymentEvent.ENTITY_TYPE);
        ev.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        ev.setSequenceNumber(1);
        ev.setCorrelationId("corr_1");
        ev.setReasonCode(null);
        ev.setOccurredAt(created);
        ev.setPaymentId("pay_mismatch_state");
        ev.setMerchantId("merch_1");
        ev.setDebtorAccountId("acc_usd_1");
        ev.setCreditorIban("RO00TEST");
        ev.setCreditorName("ACME");
        ev.setAmount(new BigDecimal("99.50"));
        ev.setCurrency("USD");
        ev.setIdempotencyKey("idem_1");

        when(paymentRepository.queryPaymentPartition(eq("pay_mismatch_state")))
                .thenReturn(CompletableFuture.completedFuture(
                        new PaymentPartitionQueryResult(head, List.of(ev))));

        assertThatThrownBy(() -> queryService.getOutboundPayment("pay_mismatch_state"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stream head does not match replayed aggregate");
    }
}
