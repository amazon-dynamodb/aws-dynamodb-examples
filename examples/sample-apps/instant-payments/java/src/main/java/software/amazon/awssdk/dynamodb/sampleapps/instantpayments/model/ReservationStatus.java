package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

/**
 * Lifecycle states of a funds {@link Reservation}.
 *
 * <p>Progression:
 * <pre>{@code
 * ACTIVE -> CONSUMED   (payment completed)
 * }</pre>
 *
 * <p>A {@code RELEASED} state (hold removed after payment rejection) is not implemented in this sample.
 */
public enum ReservationStatus {
    /** Hold is in force. {@link Account#getAvailableBalance()} was reduced. */
    ACTIVE,
    /** Payment completed. Hold converted to a final ledger debit. */
    CONSUMED
}
