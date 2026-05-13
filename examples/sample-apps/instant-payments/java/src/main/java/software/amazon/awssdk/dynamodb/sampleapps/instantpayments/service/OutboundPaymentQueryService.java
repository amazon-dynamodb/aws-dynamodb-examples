package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentPartitionQueryResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * Read-only access to outbound payments for API queries.
 *
 * <p>Loads the payment partition via {@link PaymentRepository#queryPaymentPartition(String)} and
 * folds {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent} records.
 */
@Service
public class OutboundPaymentQueryService {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;
    private final PaymentEventReplayer paymentEventReplayer;

    /**
     * @param paymentRepository   loads the payment partition
     * @param paymentMapper       builds GET response DTOs
     * @param paymentEventReplayer folds stored events into the scalar read model
     */
    public OutboundPaymentQueryService(PaymentRepository paymentRepository,
                                       PaymentMapper paymentMapper,
                                       PaymentEventReplayer paymentEventReplayer) {
        this.paymentRepository = paymentRepository;
        this.paymentMapper = paymentMapper;
        this.paymentEventReplayer = paymentEventReplayer;
    }

    /**
     * Returns the payment read model with ordered event history.
     *
     * @param paymentId logical payment id
     * @throws PaymentNotFoundException if no stream exists for the id
     */
    public GetOutboundPaymentResponse getOutboundPayment(String paymentId) {
        PaymentPartitionQueryResult partition = paymentRepository.queryPaymentPartition(paymentId).join();
        if (partition == null) {
            throw new PaymentNotFoundException(paymentId);
        }
        Payment folded = paymentEventReplayer.fold(paymentId, partition.events());
        assertHeadMatchesFold(partition.streamHead(), folded);
        return paymentMapper.toGetOutboundPaymentResponse(folded, partition.events());
    }

    /**
     * Ensures {@link PaymentStreamHead#getLastSequence()} and {@link PaymentStreamHead#getAggregateState()} match the replayed aggregate.
     *
     * @param head   authoritative concurrency row
     * @param folded aggregate from {@link PaymentEventReplayer#fold(String, java.util.List)}
     * @throws IllegalStateException on mismatch (data corruption or replay bug)
     */
    private static void assertHeadMatchesFold(PaymentStreamHead head, Payment folded) {
        if (head.getLastSequence() != folded.getVersion()
                || !head.getAggregateState().equals(folded.getState())) {
            throw new IllegalStateException(
                    "Stream head does not match replayed aggregate for payment " + folded.getPaymentId());
        }
    }
}
