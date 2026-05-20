package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import java.util.List;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;

/**
 * Paged merchant payment query result returned by repository implementations.
 *
 * @param items     stream-head items for the requested page
 * @param nextToken opaque pagination token for the next page, or {@code null} when no further page exists
 */
public record MerchantPaymentQueryResult(List<PaymentStreamHead> items, String nextToken) {

    public MerchantPaymentQueryResult {
        items = List.copyOf(items);
    }
}
