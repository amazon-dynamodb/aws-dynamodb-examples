package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentsPage;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaymentStateException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.MerchantPaymentQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;

/**
 * Read-model service for merchant-scoped payment list queries.
 *
 * <p>Delegates to GSI-backed repository methods and maps results to
 * {@link MerchantPaymentProjection} DTOs.
 */
@Service
public class MerchantPaymentQueryService {

    private static final Logger log = LoggerFactory.getLogger(MerchantPaymentQueryService.class);

    /**
     * Page size when the client omits {@code limit} or sends a non-positive value.
     */
    static final int DEFAULT_LIMIT = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    /**
     * @param paymentRepository persistence (high- or low-level per {@code dynamodb.client-type})
     * @param paymentMapper     maps repository stream-head results to {@link MerchantPaymentProjection}
     */
    public MerchantPaymentQueryService(PaymentRepository paymentRepository,
                                       PaymentMapper paymentMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
    }

    /**
     * Lists payment projections for a merchant.
     *
     * <p>When {@code scanIndexForward} is omitted or {@code false}, results are newest first
     * (DynamoDB {@code ScanIndexForward=false}). When {@code true}, oldest first.
     *
     * @param merchantId        merchant scope
     * @param limit             page size; uses {@value DEFAULT_LIMIT} when {@code null}, zero, or negative
     * @param scanIndexForward  optional; {@code true} for ascending index traversal per DynamoDB Query
     * @param nextToken         optional opaque pagination token from a previous page
     * @return ordered page of payment projections (may be empty)
     */
    public MerchantPaymentsPage listMerchantPayments(String merchantId,
                                                     Integer limit,
                                                     Boolean scanIndexForward,
                                                     String nextToken) {
        int effectiveLimit = sanitizeLimit(limit);
        boolean forward = effectiveScanIndexForward(scanIndexForward);
        log.debug("Listing merchant payments: merchantId={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, effectiveLimit, forward, nextToken != null && !nextToken.isBlank());

        MerchantPaymentQueryResult result = paymentRepository
                .queryMerchantPayments(merchantId, effectiveLimit, forward, nextToken).join();

        return new MerchantPaymentsPage(result.items().stream()
                .map(paymentMapper::toMerchantPaymentProjection)
                .toList(), result.nextToken());
    }

    /**
     * Lists payment projections for a merchant filtered by state.
     *
     * <p>Ordering follows {@link #listMerchantPayments(String, Integer, Boolean, String)}.
     *
     * @param merchantId        merchant scope
     * @param state             payment state (case-insensitive); validated against {@link PaymentState}
     * @param limit             page size; uses {@value DEFAULT_LIMIT} when {@code null}, zero, or negative
     * @param scanIndexForward  optional; {@code true} for ascending index traversal per DynamoDB Query
     * @param nextToken         optional opaque pagination token from a previous page
     * @return ordered page of matching payment projections (may be empty)
     * @throws InvalidPaymentStateException if {@code state} is not a recognised value
     */
    public MerchantPaymentsPage listMerchantPaymentsByState(String merchantId,
                                                            String state,
                                                            Integer limit,
                                                            Boolean scanIndexForward,
                                                            String nextToken) {
        String normalizedState = validateAndNormalizeState(state);
        int effectiveLimit = sanitizeLimit(limit);
        boolean forward = effectiveScanIndexForward(scanIndexForward);
        log.debug("Listing merchant payments by state: merchantId={}, state={}, limit={}, scanIndexForward={}, nextTokenPresent={}",
                merchantId, normalizedState, effectiveLimit, forward, nextToken != null && !nextToken.isBlank());

        MerchantPaymentQueryResult result = paymentRepository
                .queryMerchantPaymentsByState(merchantId, normalizedState, effectiveLimit, forward, nextToken).join();

        return new MerchantPaymentsPage(result.items().stream()
                .map(paymentMapper::toMerchantPaymentProjection)
                .toList(), result.nextToken());
    }

    /**
     * @param limit raw query parameter (may be {@code null})
     * @return {@link #DEFAULT_LIMIT} when null or non-positive; otherwise {@code limit}
     */
    private static int sanitizeLimit(Integer limit) {
        return (limit == null || limit <= 0) ? DEFAULT_LIMIT : limit;
    }

    /**
     * @param scanIndexForward raw query parameter (may be {@code null})
     * @return {@code true} only when the client sends {@code true}; otherwise {@code false} (newest first)
     */
    private static boolean effectiveScanIndexForward(Boolean scanIndexForward) {
        return Boolean.TRUE.equals(scanIndexForward);
    }

    /**
     * @param state raw path segment (case-insensitive)
     * @return upper-case {@link PaymentState} name
     * @throws InvalidPaymentStateException if not a known enum constant
     */
    private static String validateAndNormalizeState(String state) {
        String upper = state.toUpperCase();
        try {
            PaymentState.valueOf(upper);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentStateException(state);
        }
        return upper;
    }
}
