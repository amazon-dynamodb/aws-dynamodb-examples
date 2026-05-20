package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentCreationResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.IdempotencyConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.HashUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.IdempotencyCanonicalizer;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboundPaymentService}, covering new payment creation, idempotent retries,
 * conflict detection, and transaction failure propagation.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class OutboundPaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    private OutboundPaymentService service;

    /** Constructs the service with a fresh mapper for each test method. */
    @BeforeEach
    void setUp() {
        service = new OutboundPaymentService(paymentRepository, new PaymentMapper());
    }

    @Test
    void createOutboundPayment_whenNewRequest_shouldCreateNewPayment() {
        when(paymentRepository.createPaymentTransaction(
                any(PaymentStreamHead.class), any(PaymentEvent.class), any(IdempotencyRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        var request = sampleRequest("key-1");

        PaymentCreationResult result = service.createOutboundPayment(request);

        assertThat(result.newlyCreated()).isTrue();
        assertThat(result.response().paymentId()).startsWith("pay_");
        assertThat(result.response().state()).isEqualTo(PaymentState.RECEIVED.name());
        assertThat(result.response().correlationId()).startsWith("corr_");
        Instant createdAt = result.response().createdAtUtc();

        ArgumentCaptor<PaymentStreamHead> headCaptor = ArgumentCaptor.forClass(PaymentStreamHead.class);
        ArgumentCaptor<IdempotencyRecord> idemCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(paymentRepository).createPaymentTransaction(
                headCaptor.capture(), any(PaymentEvent.class), idemCaptor.capture());

        assertThat(headCaptor.getValue().getCreatedAtUtc()).isEqualTo(createdAt);
        assertThat(idemCaptor.getValue().getCreatedAtUtc()).isEqualTo(createdAt);
    }

    @Test
    void createOutboundPayment_whenIdempotentRetry_shouldReturnStoredResponse() {
        var request = sampleRequest("key-dup");
        String requestHash = HashUtils.sha256(IdempotencyCanonicalizer.canonicalForm(request));

        CreateOutboundPaymentResponse storedResponse = new CreateOutboundPaymentResponse(
                "pay_existing", "RECEIVED", "corr_existing", Instant.parse("2026-03-18T10:15:30Z"));

        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash(requestHash);
        existing.setResponseSnapshot(storedResponse);

        TransactionCanceledException tce = buildIdempotencyConflictException();

        when(paymentRepository.createPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));
        when(paymentRepository.getIdempotencyRecord("key-dup"))
                .thenReturn(CompletableFuture.completedFuture(existing));

        PaymentCreationResult result = service.createOutboundPayment(request);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.response().paymentId()).isEqualTo("pay_existing");
    }

    @Test
    void createOutboundPayment_whenHashMismatch_shouldThrowConflict() {
        var request = sampleRequest("key-conflict");

        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setRequestHash("different-hash-value");
        existing.setResponseSnapshot(new CreateOutboundPaymentResponse(
                "pay_other", "RECEIVED", "corr", Instant.EPOCH));

        TransactionCanceledException tce = buildIdempotencyConflictException();

        when(paymentRepository.createPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));
        when(paymentRepository.getIdempotencyRecord("key-conflict"))
                .thenReturn(CompletableFuture.completedFuture(existing));

        assertThatThrownBy(() -> service.createOutboundPayment(request))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("key-conflict");
    }

    @Test
    void createOutboundPayment_whenUnexpectedTransactionFailure_shouldPropagateFailure() {
        when(paymentRepository.createPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DynamoDB unavailable")));

        var request = sampleRequest("key-fail");

        assertThatThrownBy(() -> service.createOutboundPayment(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create payment transaction")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void createOutboundPayment_whenIdempotencyRecordMissingAfterConflict_shouldThrowIllegalState() {
        var request = sampleRequest("key-missing-record");
        TransactionCanceledException tce = buildIdempotencyConflictException();

        when(paymentRepository.createPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));
        when(paymentRepository.getIdempotencyRecord("key-missing-record"))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.createOutboundPayment(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Idempotency record not found");
    }

    @Test
    void createOutboundPayment_whenTransactionCanceledWithShortReasons_shouldNotTreatAsIdempotencyConflict() {
        var request = sampleRequest("key-short-reasons");
        TransactionCanceledException tce = TransactionCanceledException.builder()
                .cancellationReasons(
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("None").build())
                .message("Transaction cancelled")
                .build();

        when(paymentRepository.createPaymentTransaction(any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(tce));

        assertThatThrownBy(() -> service.createOutboundPayment(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create payment transaction")
                .hasCauseInstanceOf(TransactionCanceledException.class);
    }

    /** Returns a minimal valid create request with the given idempotency key. */
    private CreateOutboundPaymentRequest sampleRequest(String idempotencyKey) {
        return new CreateOutboundPaymentRequest(
                idempotencyKey, "merch_1", "acc_usd_1", "RO49AAAA1B31007593840000",
                "John Doe", new BigDecimal("100"), "USD");
    }

    /**
     * Builds a {@link TransactionCanceledException} whose third cancellation reason is a conditional
     * check failure, matching idempotency record already exists semantics.
     */
    private TransactionCanceledException buildIdempotencyConflictException() {
        return TransactionCanceledException.builder()
                .cancellationReasons(
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("ConditionalCheckFailed")
                                .message("The conditional request failed").build())
                .message("Transaction cancelled")
                .build();
    }
}
