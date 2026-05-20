package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller.MerchantPaymentIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.controller.MerchantPaymentLowLevelIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke.controller.MerchantPaymentSmokeTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentsPage;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaymentStateException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.MerchantPaymentQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.MerchantPaymentQueryService;

/**
 * Unit tests for {@link MerchantPaymentQueryService}.
 *
 * <p>Repository implementations are exercised by {@link MerchantPaymentIntegrationTest},
 * {@link MerchantPaymentLowLevelIntegrationTest},
 * and {@link MerchantPaymentSmokeTest}.
 *
 * <p>Verifies limit sanitization, {@code scanIndexForward} handling, next-token pass-through,
 * state validation/normalization, and correct delegation to the repository and mapper layers.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
public class MerchantPaymentQueryServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Spy
    private PaymentMapper paymentMapper;

    @InjectMocks
    private MerchantPaymentQueryService merchantPaymentQueryService;

    @Test
    void listMerchantPayments_whenPaymentsExist_shouldReturnPagedProjections() {
        Instant created1 = Instant.parse("2026-03-18T10:15:30Z");
        Instant created2 = Instant.parse("2026-03-18T10:16:00Z");

        PaymentStreamHead head1 = buildHead("pay_1", "merch_1", "COMPLETED", created1);
        PaymentStreamHead head2 = buildHead("pay_2", "merch_1", "RECEIVED", created2);

        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(
                        new MerchantPaymentQueryResult(List.of(head1, head2), "token-1")));

        MerchantPaymentsPage result =
                merchantPaymentQueryService.listMerchantPayments("merch_1", null, null, null);

        assertThat(result.nextToken()).isEqualTo("token-1");
        assertThat(result.items()).hasSize(2);

        MerchantPaymentProjection first = result.items().getFirst();
        assertThat(first.paymentId()).isEqualTo("pay_1");
        assertThat(first.state()).isEqualTo("COMPLETED");
        assertThat(first.version()).isEqualTo(3);
        assertThat(first.merchantId()).isEqualTo("merch_1");
        assertThat(first.correlationId()).isEqualTo("corr_pay_1");
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.createdAtUtc()).isEqualTo(created1);
        assertThat(first.updatedAtUtc()).isEqualTo(created1.plusSeconds(10));

        assertThat(result.items().get(1).paymentId()).isEqualTo("pay_2");
        assertThat(result.items().get(1).state()).isEqualTo("RECEIVED");
    }

    @Test
    void listMerchantPayments_whenNoPaymentsExist_shouldReturnEmptyPage() {
        when(paymentRepository.queryMerchantPayments(eq("merch_empty"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        MerchantPaymentsPage result =
                merchantPaymentQueryService.listMerchantPayments("merch_empty", null, null, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextToken()).isNull();
    }

    @Test
    void listMerchantPayments_whenLimitNull_shouldUseDefault50() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", null, null, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull());
    }

    @Test
    void listMerchantPayments_whenLimitZero_shouldUseDefault50() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", 0, null, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull());
    }

    @Test
    void listMerchantPayments_whenLimitNegative_shouldUseDefault50() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", -5, null, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull());
    }

    @Test
    void listMerchantPayments_whenValidLimitProvided_shouldRespectExplicitLimit() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(10), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", 10, null, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(10), eq(false), isNull());
    }

    @Test
    void listMerchantPayments_whenNextTokenProvided_shouldPassToRepository() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), eq("token-1")))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", null, null, "token-1");

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(false), eq("token-1"));
    }

    @Test
    void listMerchantPayments_whenScanIndexForwardTrue_shouldPassTrueToRepository() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(true), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", null, true, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(true), isNull());
    }

    @Test
    void listMerchantPayments_whenScanIndexForwardFalse_shouldPassFalseToRepository() {
        when(paymentRepository.queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPayments("merch_1", null, false, null);

        verify(paymentRepository).queryMerchantPayments(eq("merch_1"), eq(50), eq(false), isNull());
    }

    @Test
    void listMerchantPaymentsByState_whenValidStateProvided_shouldReturnFilteredPage() {
        Instant created = Instant.parse("2026-03-18T10:15:30Z");
        PaymentStreamHead head = buildHead("pay_1", "merch_1", "COMPLETED", created);

        when(paymentRepository.queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(
                        new MerchantPaymentQueryResult(List.of(head), "token-2")));

        MerchantPaymentsPage result =
                merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "completed", null, null, null);

        assertThat(result.nextToken()).isEqualTo("token-2");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().paymentId()).isEqualTo("pay_1");
        assertThat(result.items().getFirst().state()).isEqualTo("COMPLETED");
    }

    @Test
    void listMerchantPaymentsByState_whenStateCaseDiffers_shouldMatchCaseInsensitively() {
        when(paymentRepository.queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "CoMpLeTeD", null, null, null);

        verify(paymentRepository).queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(false), isNull());
    }

    @Test
    void listMerchantPaymentsByState_whenStateInvalid_shouldThrow() {
        assertThatThrownBy(() ->
                merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "BOGUS", null, null, null))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("BOGUS");
    }

    @Test
    void listMerchantPaymentsByState_whenNoPaymentsExist_shouldReturnEmptyPage() {
        when(paymentRepository.queryMerchantPaymentsByState(eq("merch_1"), eq("RECEIVED"), eq(50),
                eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        MerchantPaymentsPage result =
                merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "RECEIVED", null, null, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.nextToken()).isNull();
    }

    @Test
    void listMerchantPaymentsByState_whenNextTokenProvided_shouldPassToRepository() {
        when(paymentRepository.queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(false), eq("token-2")))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "COMPLETED", null, null, "token-2");

        verify(paymentRepository).queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(false), eq("token-2"));
    }

    @Test
    void listMerchantPaymentsByState_whenScanIndexForwardTrue_shouldPassTrueToRepository() {
        when(paymentRepository.queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(true), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new MerchantPaymentQueryResult(List.of(), null)));

        merchantPaymentQueryService.listMerchantPaymentsByState("merch_1", "COMPLETED", null, true, null);

        verify(paymentRepository).queryMerchantPaymentsByState(eq("merch_1"), eq("COMPLETED"), eq(50),
                eq(true), isNull());
    }

    /** Builds a {@link PaymentStreamHead} suitable for merchant list projection tests. */
    private static PaymentStreamHead buildHead(String paymentId, String merchantId,
                                               String state, Instant createdAtUtc) {
        PaymentStreamHead head = new PaymentStreamHead();
        head.setPaymentKey("PAYMENT#" + paymentId);
        head.setStreamKey("#HEAD");
        head.setEntityType("PAYMENT_STREAM_HEAD");
        head.setLastSequence(3);
        head.setAggregateState(state);
        head.setUpdatedAtUtc(createdAtUtc.plusSeconds(10));
        head.setPaymentId(paymentId);
        head.setMerchantId(merchantId);
        head.setCreatedAtUtc(createdAtUtc);
        head.setCorrelationId("corr_" + paymentId);
        head.setAmount(new BigDecimal("100.00"));
        head.setCurrency("USD");
        return head;
    }
}
