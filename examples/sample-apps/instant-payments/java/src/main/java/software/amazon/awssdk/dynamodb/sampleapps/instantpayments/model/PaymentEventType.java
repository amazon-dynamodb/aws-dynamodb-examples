package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Domain events persisted for the outbound payment aggregate (append-only stream).
 */
public enum PaymentEventType {

    /** Payment shell created; debtor, amount, and idempotency metadata recorded. */
    OUTBOUND_PAYMENT_CREATED,

    /** Funds reserved on the debtor account; payment moves to {@link PaymentState#FUNDS_RESERVED}. */
    FUNDS_RESERVED,

    /** Settlement finished; payment {@link PaymentState#COMPLETED}. */
    COMPLETED,

    /** Terminal failure (validation, insufficient funds, release after hold, etc.). */
    REJECTED
}
