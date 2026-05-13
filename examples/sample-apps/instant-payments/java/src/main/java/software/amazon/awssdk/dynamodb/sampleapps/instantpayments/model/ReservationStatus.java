package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Lifecycle states of a funds {@link Reservation}.
 *
 * <p>Progression:
 * <pre>{@code
 * ACTIVE -> CONSUMED   (payment completed)
 *       \-> RELEASED (payment rejected after reserve)
 * }</pre>
 */
public enum ReservationStatus {
    /** Hold is in force; {@link Account#getAvailableBalance()} was reduced. */
    ACTIVE,
    /** Payment completed; hold converted to a final ledger debit. */
    CONSUMED,
    /** Payment rejected; hold removed and available balance restored. */
    RELEASED
}
