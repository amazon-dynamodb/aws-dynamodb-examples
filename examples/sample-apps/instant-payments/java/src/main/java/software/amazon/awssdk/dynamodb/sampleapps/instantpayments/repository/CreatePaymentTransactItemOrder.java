package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository;

import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Names the order of items in the create payment {@code TransactWriteItems} request.
 *
 * <p>{@link PaymentRepository#createPaymentTransaction} builds the transact items in a single fixed
 * order shared by every implementation: the payment stream head put, the first domain event put, and
 * the conditional idempotency put ({@code attribute_not_exists(PK)}). DynamoDB reports per-item
 * cancellation reasons in the same order, so the {@linkplain #index() index} of each constant doubles
 * as the position of that item's reason in
 * {@link TransactionCanceledException#cancellationReasons()}.
 *
 * <p>The producers ({@code LowLevelDynamoDbPaymentRepository}, {@code HighLevelDynamoDbPaymentRepository})
 * and the consumer ({@code OutboundPaymentService.isIdempotencyConflict}) all reference this enum, so
 * the transact item order is defined once. Reordering the items means reordering these constants, which
 * keeps both ends aligned instead of letting a producer change silently break conflict detection.
 *
 * <p>Constants are declared in the same order the items are written, so {@link Enum#ordinal()} equals
 * {@link #index()}.
 */
public enum CreatePaymentTransactItemOrder {

    /** Unconditional put of the payment stream head. */
    STREAM_HEAD,

    /** Unconditional put of the first domain event ({@code OUTBOUND_PAYMENT_CREATED}). */
    FIRST_EVENT,

    /** Conditional put of the idempotency record ({@code attribute_not_exists(PK)}). */
    IDEMPOTENCY;

    /**
     * @return zero-based position of this item in the create transact write and in the matching
     *     cancellation-reason list, equal to {@link Enum#ordinal()}
     */
    public int index() {
        return ordinal();
    }
}
