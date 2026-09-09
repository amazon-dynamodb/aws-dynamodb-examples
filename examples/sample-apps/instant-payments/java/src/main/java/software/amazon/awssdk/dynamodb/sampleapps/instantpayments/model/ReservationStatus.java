package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Lifecycle states of a funds {@link Reservation}.
 *
 * <p>Progression:
 * <pre>{@code
 * ACTIVE -> CONSUMED   (payment completed, hold converted to a settled debit)
 * ACTIVE -> RELEASED   (temporary reservation expired and the streams listener restored available balance)
 * }</pre>
 *
 * <p>A reservation only ever leaves {@code ACTIVE} once. The complete transact and the TTL driven
 * release both condition on {@code status = ACTIVE} against the same audit reservation item, so
 * exactly one of {@code CONSUMED} or {@code RELEASED} wins and the held funds are never double counted.
 */
public enum ReservationStatus {
    /** Hold is in force. {@link Account#getAvailableBalance()} was reduced. */
    ACTIVE,
    /** Payment completed. Hold converted to a final ledger debit. */
    CONSUMED,
    /**
     * Hold expired before the payment completed. The temporary reservation was deleted by DynamoDB and the
     * streams listener restored {@link Account#getAvailableBalance()}. The audit row is kept for audit.
     */
    RELEASED
}
