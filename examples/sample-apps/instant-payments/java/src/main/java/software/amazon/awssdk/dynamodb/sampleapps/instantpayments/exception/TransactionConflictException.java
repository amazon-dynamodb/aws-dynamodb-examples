package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;

/**
 * Internal control-flow signal that a {@code TransactWriteItems} call was canceled by a concurrent
 * {@code TransactionConflict} (a competing transaction on the same item) rather than a precondition
 * failure.
 *
 * <p>Thrown by the reserve, complete, and reject steps in {@link OutboundPaymentProcessor} and caught
 * by {@link OutboundPaymentProcessor#processPayment(String)} so the whole flow is re-loaded and
 * retried. The SDK retry strategy does not retry this cancellation reason, so the processor retries it
 * in-call up to {@link OutboundPaymentProcessor#MAX_TRANSACTION_CONFLICT_RETRIES} times.
 *
 * <p>Internal to the processing flow and never surfaced to callers, exhausted retries are rewrapped as
 * a {@link RuntimeException}.
 */
public class TransactionConflictException extends RuntimeException {

    /**
     * @param cause underlying {@code TransactionCanceledException} carrying the conflict reason
     */
    public TransactionConflictException(Throwable cause) {
        super(cause);
    }
}
