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
    /** Payment accepted and persisted; funds not yet reserved. */
    RECEIVED,
    /** Funds reserved on the debtor account; ready to settle or reject with release. */
    FUNDS_RESERVED,
    /** Funds moved to settled state (ledger debit applied, reservation consumed). */
    COMPLETED,
    /** Validation or business rule failed; no success settlement (may follow RECEIVED or FUNDS_RESERVED). */
    REJECTED
}
