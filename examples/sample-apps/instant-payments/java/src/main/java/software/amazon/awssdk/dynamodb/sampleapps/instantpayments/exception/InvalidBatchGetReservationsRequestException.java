package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when a batch-get reservations request is semantically invalid before the repository call.
 *
 * <p>This is used for request-shape rules that are specific to the batch-get reservations API but do
 * not naturally fit bean-validation annotations on the raw JSON body. Handled by
 * {@link GlobalExceptionHandler#handleInvalidBatchGetReservationsRequest(InvalidBatchGetReservationsRequestException)}
 * as HTTP 400 Bad Request.
 */
public class InvalidBatchGetReservationsRequestException extends RuntimeException {

    /**
     * Creates the exception with a client-safe validation message.
     *
     * @param message validation failure message safe to return to API callers
     */
    public InvalidBatchGetReservationsRequestException(String message) {
        super(message);
    }
}



