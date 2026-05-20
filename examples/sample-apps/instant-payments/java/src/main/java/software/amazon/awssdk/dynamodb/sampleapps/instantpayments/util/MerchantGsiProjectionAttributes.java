package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.util.List;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

/**
 * Shared DynamoDB attribute names for merchant-facing global secondary index projections.
 *
 * <p>{@link PaymentStreamHead#GSI_MERCHANT_STATE_PAYMENTS} uses {@link ProjectionType#INCLUDE}. The names
 * listed in {@link #GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS} are the non-key attributes replicated on
 * that index beyond table and index keys. Runtime table creation and test fixtures must use the same list
 * so merchant list APIs return a complete {@link MerchantPaymentProjection}.
 */
public final class MerchantGsiProjectionAttributes {

    /**
     * Prevents instantiation. This type only carries shared projection metadata.
     */
    private MerchantGsiProjectionAttributes() {
    }

    /**
     * Non-key attributes projected on {@link PaymentStreamHead#GSI_MERCHANT_STATE_PAYMENTS}
     * beyond table and index keys, aligned with {@link MerchantPaymentProjection}.
     */
    public static final List<String> GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS = List.of(
            "paymentId",
            "lastSequence",
            "correlationId",
            "amount",
            "currency",
            "updatedAtUtc",
            "reasonCode");
}
