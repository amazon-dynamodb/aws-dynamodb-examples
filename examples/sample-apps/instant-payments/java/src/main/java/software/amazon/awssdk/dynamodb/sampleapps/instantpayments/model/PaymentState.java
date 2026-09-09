package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Lifecycle states of an outbound payment.
 *
 * <p>Typical progression:
 * <pre>{@code
 * RECEIVED -> FUNDS_RESERVED -> COMPLETED
 *                    \-> REJECTED
 * }</pre>
 */
public enum PaymentState {
    /** Payment accepted and persisted. Funds not yet reserved. */
    RECEIVED,
    /** Funds reserved on the debtor account. Ready to settle or reject with release. */
    FUNDS_RESERVED,
    /** Funds moved to settled state (ledger debit applied, reservation consumed). */
    COMPLETED,
    /** Validation or business rule failed. No success settlement (may follow RECEIVED or FUNDS_RESERVED). */
    REJECTED
}
