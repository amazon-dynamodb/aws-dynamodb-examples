package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * Read-only access to outbound payments for API queries.
 *
 * <p>Loads the payment partition via {@link PaymentRepository#queryPaymentPartition(String)} and
 * folds {@link PaymentEvent} records.
 *
 * <p>Returns {@link CompletableFuture} so async MVC controllers can compose without blocking Tomcat
 * worker threads.
 */
@Service
public class OutboundPaymentQueryService {

    /** Loads payment partitions from DynamoDB. */
    private final PaymentRepository paymentRepository;
    /** Builds GET response DTOs from folded payments and events. */
    private final PaymentMapper paymentMapper;
    /** Folds stored events into the scalar payment read model. */
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
     * @return response DTO future
     */
    public CompletableFuture<GetOutboundPaymentResponse> getOutboundPayment(String paymentId) {
        return paymentRepository.queryPaymentPartition(paymentId)
                .thenApply(partition -> {
                    if (partition == null) {
                        throw new PaymentNotFoundException(paymentId);
                    }
                    Payment folded = paymentEventReplayer.fold(paymentId, partition.events());
                    assertHeadMatchesFold(partition.streamHead(), folded);
                    return paymentMapper.toGetOutboundPaymentResponse(folded, partition.events());
                });
    }

    /**
     * Ensures {@link PaymentStreamHead#getLastSequence()} and {@link PaymentStreamHead#getAggregateState()} match the replayed aggregate.
     *
     * @param head   authoritative concurrency row
     * @param folded aggregate from {@link PaymentEventReplayer#fold(String, List)}
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
