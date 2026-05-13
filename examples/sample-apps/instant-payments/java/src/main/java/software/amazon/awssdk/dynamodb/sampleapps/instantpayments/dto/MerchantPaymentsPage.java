package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Paged response envelope for merchant payment list endpoints.
 *
 * @param items     merchant payment projections for the requested page
 * @param nextToken opaque pagination token for the next page; {@code null} when no further page exists
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MerchantPaymentsPage(List<MerchantPaymentProjection> items, String nextToken) {

    public MerchantPaymentsPage {
        items = List.copyOf(items);
    }
}
