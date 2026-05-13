package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;

/**
 * Unit tests for {@link PaymentMapper}, covering initial payment artifacts, merchant list
 * projections, and idempotency record snapshots.
 */
@Tag("unit")
public class PaymentMapperTest {

    private PaymentMapper mapper;

    @BeforeEach
    void setUpMapper() {
        mapper = new PaymentMapper();
        ReflectionTestUtils.setField(mapper, "idempotencyTtlSeconds", 2_592_000L);
        ReflectionTestUtils.invokeMethod(mapper, "validateIdempotencyTtlConfiguration");
    }

    @Test
    void toInitialStreamHead_shouldSetConcurrencyFields() {
        Instant now = Instant.parse("2026-03-18T10:15:30Z");
        var request = new CreateOutboundPaymentRequest(
                "idem-key-1", "merch_1", "acc_usd_1", "RO49AAAA1B31007593840000",
                "John Doe", new BigDecimal("100.50"), "USD");

        PaymentStreamHead head = mapper.toInitialStreamHead(request, "pay_123", "corr_123", now);

        assertThat(head.getPaymentKey()).isEqualTo("PAYMENT#pay_123");
        assertThat(head.getStreamKey()).isEqualTo(PaymentStreamHead.SORT_KEY);
        assertThat(head.getEntityType()).isEqualTo(PaymentStreamHead.ENTITY_TYPE);
        assertThat(head.getLastSequence()).isEqualTo(1);
        assertThat(head.getAggregateState()).isEqualTo(PaymentState.RECEIVED.name());
        assertThat(head.getUpdatedAtUtc()).isEqualTo(now);
        assertThat(head.getPaymentId()).isEqualTo("pay_123");
        assertThat(head.getMerchantId()).isEqualTo("merch_1");
        assertThat(head.getCreatedAtUtc()).isEqualTo(now);
        assertThat(head.getCorrelationId()).isEqualTo("corr_123");
        assertThat(head.getAmount()).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(head.getCurrency()).isEqualTo("USD");
    }

    @Test
    void toOutboundPaymentCreatedEvent_shouldRecordFullShell() {
        var request = new CreateOutboundPaymentRequest(
                "idem-key-1", "merch_1", "acc_usd_1", "RO49AAAA1B31007593840000",
                "John Doe", new BigDecimal("100.50"), "USD");
        Instant now = Instant.parse("2026-03-18T10:15:30Z");

        PaymentEvent event = mapper.toOutboundPaymentCreatedEvent(request, "pay_123", "corr_456", now);

        assertThat(event.getPaymentKey()).isEqualTo("PAYMENT#pay_123");
        assertThat(event.getEventKey()).isEqualTo(PaymentEvent.sortKeyForSequence(1));
        assertThat(event.getEntityType()).isEqualTo(PaymentEvent.ENTITY_TYPE);
        assertThat(event.getEventType()).isEqualTo(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        assertThat(event.getSequenceNumber()).isEqualTo(1);
        assertThat(event.getCorrelationId()).isEqualTo("corr_456");
        assertThat(event.getOccurredAt()).isEqualTo(now);
        assertThat(event.getPaymentId()).isEqualTo("pay_123");
        assertThat(event.getMerchantId()).isEqualTo("merch_1");
        assertThat(event.getDebtorAccountId()).isEqualTo("acc_usd_1");
        assertThat(event.getCreditorIban()).isEqualTo("RO49AAAA1B31007593840000");
        assertThat(event.getCreditorName()).isEqualTo("John Doe");
        assertThat(event.getAmount()).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(event.getCurrency()).isEqualTo("USD");
        assertThat(event.getIdempotencyKey()).isEqualTo("idem-key-1");
    }

    /**
     * Merchant list DTO fields must map one-to-one from a fully populated stream head (GSI query result shape).
     */
    @Test
    void toMerchantPaymentProjection_mapsAllMerchantListFields() {
        Instant created = Instant.parse("2026-04-21T10:00:00Z");
        Instant updated = Instant.parse("2026-04-21T10:05:00Z");
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentId("pay_x");
        head.setAggregateState(PaymentState.COMPLETED.name());
        head.setLastSequence(3);
        head.setMerchantId("merch_1");
        head.setCorrelationId("corr_y");
        head.setAmount(new BigDecimal("10.00"));
        head.setCurrency("USD");
        head.setCreatedAtUtc(created);
        head.setUpdatedAtUtc(updated);
        head.setReasonCode(null);

        MerchantPaymentProjection projection = mapper.toMerchantPaymentProjection(head);

        assertThat(projection.paymentId()).isEqualTo("pay_x");
        assertThat(projection.state()).isEqualTo(PaymentState.COMPLETED.name());
        assertThat(projection.version()).isEqualTo(3);
        assertThat(projection.merchantId()).isEqualTo("merch_1");
        assertThat(projection.correlationId()).isEqualTo("corr_y");
        assertThat(projection.amount()).isEqualByComparingTo("10.00");
        assertThat(projection.currency()).isEqualTo("USD");
        assertThat(projection.createdAtUtc()).isEqualTo(created);
        assertThat(projection.updatedAtUtc()).isEqualTo(updated);
        assertThat(projection.reasonCode()).isNull();
    }

    /**
     * When {@code updatedAtUtc} is absent on a sparse index item, the API projection must surface null.
     */
    @Test
    void toMerchantPaymentProjection_allowsNullUpdatedAtUtc() {
        Instant created = Instant.parse("2026-04-21T10:00:00Z");
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentId("pay_sparse");
        head.setAggregateState(PaymentState.RECEIVED.name());
        head.setLastSequence(1);
        head.setMerchantId("merch_1");
        head.setCorrelationId("corr_z");
        head.setAmount(new BigDecimal("1.00"));
        head.setCurrency("EUR");
        head.setCreatedAtUtc(created);
        head.setUpdatedAtUtc(null);
        head.setReasonCode(null);

        MerchantPaymentProjection projection = mapper.toMerchantPaymentProjection(head);

        assertThat(projection.updatedAtUtc()).isNull();
        assertThat(projection.paymentId()).isEqualTo("pay_sparse");
    }

    @Test
    void toReservationResponse_mapsAllReservationFields() {
        Instant created = Instant.parse("2026-04-21T10:15:33Z");
        Reservation reservation = new Reservation();
        reservation.setReservationId("res_pay_123");
        reservation.setPaymentId("pay_123");
        reservation.setAmount(new BigDecimal("42.50"));
        reservation.setStatus("CONSUMED");
        reservation.setCreatedAtUtc(created);

        var response = mapper.toReservationResponse(reservation);

        assertThat(response.reservationId()).isEqualTo("res_pay_123");
        assertThat(response.paymentId()).isEqualTo("pay_123");
        assertThat(response.amount()).isEqualByComparingTo("42.50");
        assertThat(response.status()).isEqualTo("CONSUMED");
        assertThat(response.createdAtUtc()).isEqualTo(created);
    }

    /**
     * Optional reason codes should remain null in both the folded payment DTO and nested event DTOs.
     */
    @Test
    void toGetOutboundPaymentResponse_preservesNullOptionalReasonCodes() {
        Instant created = Instant.parse("2026-04-21T11:00:00Z");
        Instant updated = Instant.parse("2026-04-21T11:03:00Z");

        var payment = new software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment();
        payment.setPaymentId("pay_optional");
        payment.setState(PaymentState.RECEIVED.name());
        payment.setCorrelationId("corr_optional");
        payment.setCreatedAtUtc(created);
        payment.setUpdatedAtUtc(updated);
        payment.setDebtorAccountId("acc_usd_1");
        payment.setCreditorIban("RO49AAAA1B31007593840000");
        payment.setCreditorName("John Doe");
        payment.setAmount(new BigDecimal("5.00"));
        payment.setCurrency("USD");
        payment.setIdempotencyKey("idem-optional");
        payment.setReasonCode(null);
        payment.setVersion(1);

        PaymentEvent event = new PaymentEvent();
        event.setEventKey(PaymentEvent.sortKeyForSequence(1));
        event.setEventType(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
        event.setReasonCode(null);
        event.setCorrelationId("corr_optional");

        var response = mapper.toGetOutboundPaymentResponse(payment, java.util.List.of(event));

        assertThat(response.reasonCode()).isNull();
        assertThat(response.events()).singleElement().satisfies(eventResponse -> {
            assertThat(eventResponse.reasonCode()).isNull();
            assertThat(eventResponse.eventType()).isEqualTo(PaymentEventType.OUTBOUND_PAYMENT_CREATED.name());
            assertThat(eventResponse.correlationId()).isEqualTo("corr_optional");
        });
    }

    @Test
    void toIdempotencyItem_shouldMapKeysRequestHashAndSnapshot() {
        // Mapper requires expiresAt > Instant.now(); a fixed historical instant eventually falls past TTL.
        Instant createdAt = Instant.now();
        CreateOutboundPaymentResponse responseSnapshot = new CreateOutboundPaymentResponse(
                "pay_123", "RECEIVED", "corr_456", createdAt);

        IdempotencyRecord record = mapper.toIdempotencyItem(
                "idem-key-1", "abc123hash", responseSnapshot, createdAt);

        assertThat(record.getIdempotencyRecordKey()).isEqualTo("IDEMPOTENCY#idem-key-1");
        assertThat(record.getEntityKey()).isEqualTo("IDEMPOTENCY");
        assertThat(record.getEntityType()).isEqualTo("IDEMPOTENCY");
        assertThat(record.getRequestHash()).isEqualTo("abc123hash");
        assertThat(record.getResponseSnapshot()).isEqualTo(responseSnapshot);
        assertThat(record.getResponseSnapshot().paymentId()).isEqualTo("pay_123");
        assertThat(record.getCreatedAtUtc()).isEqualTo(createdAt);
        assertThat(record.getExpiresAtEpochSecond()).isEqualTo(createdAt.getEpochSecond() + 2_592_000L);
    }
}
