package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.util.List;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;

/**
 * All items stored under the payment partition key ({@code PK=PAYMENT#{paymentId}}):
 * the stream head (concurrency) and append-only {@link PaymentEvent} history.
 *
 * <p>Produced by {@link PaymentRepository#queryPaymentPartition(String)} using a single-table
 * {@code Query} (item collection pattern).
 *
 * @param streamHead concurrency row, never {@code null} on a non-null result
 * @param events    domain events sorted by {@link PaymentEvent#getSequenceNumber()}
 */
public record PaymentPartitionQueryResult(PaymentStreamHead streamHead, List<PaymentEvent> events) {
}
