package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Lifecycle states of a funds {@link Reservation}.
 *
 * <p>Progression:
 * <pre>{@code
 * ACTIVE -> CONSUMED   (payment completed, hold converted to a settled debit)
 * ACTIVE -> RELEASED   (hold expired and was swept, available balance restored)
 * }</pre>
 *
 * <p>A reservation only ever leaves {@code ACTIVE} once. The complete transact and the expiry
 * sweeper both condition on {@code status = ACTIVE} against the same reservation item, so exactly
 * one of {@code CONSUMED} or {@code RELEASED} wins and the held funds are never double-counted.
 */
public enum ReservationStatus {
    /** Hold is in force. {@link Account#getAvailableBalance()} was reduced. */
    ACTIVE,
    /** Payment completed. Hold converted to a final ledger debit. */
    CONSUMED,
    /**
     * Hold expired before the payment completed and was swept. {@link Account#getAvailableBalance()}
     * was restored. The row is kept for audit. DynamoDB does not delete it.
     */
    RELEASED
}
