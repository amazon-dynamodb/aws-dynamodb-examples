package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Application-level retry for {@code TransactWriteItems} that DynamoDB cancels with reason
 * {@code TransactionConflict}.
 *
 * <p>The SDK retry strategy does not retry a transaction cancelled by a serializable conflict between
 * concurrent transactions on the same item. This helper retries the whole supplied operation so each
 * attempt re-reads state and rebuilds the transact items with a fresh optimistic-lock version.
 *
 * <p>Only {@link TransactionCanceledException} carrying a {@code TransactionConflict} reason is
 * retried. A {@code ConditionalCheckFailed} cancellation (stale version, insufficient funds, or an
 * idempotent replay) is a real outcome, not a conflict, so it propagates unchanged on the first throw.
 */
public final class TransactionRetry {

    private static final Logger logger = LoggerFactory.getLogger(TransactionRetry.class);

    /** Maximum number of attempts, including the first, before a conflict propagates. */
    public static final int MAX_ATTEMPTS = 3;

    /** DynamoDB cancellation reason code for a serializable transaction conflict. */
    static final String TRANSACTION_CONFLICT_CODE = "TransactionConflict";

    /** Base backoff in milliseconds, grown exponentially per attempt and jittered. */
    private static final long BASE_BACKOFF_MS = 25;

    /** Not instantiated. */
    private TransactionRetry() {
    }

    /**
     * Runs {@code operation}, retrying it when it fails with a {@code TransactionConflict}
     * cancellation, up to {@link #MAX_ATTEMPTS} attempts with short jittered backoff.
     *
     * @param operation the read, build, and transact unit to run (and re-run on conflict)
     * @param <T>       the operation result type
     * @return the result of the first successful attempt
     * @throws TransactionCanceledException if the conflict persists after the final attempt, or for a
     *                                      cancellation that is not a {@code TransactionConflict}
     */
    public static <T> T runWithConflictRetry(Supplier<T> operation) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return operation.get();
            } catch (TransactionCanceledException ex) {
                if (attempt >= MAX_ATTEMPTS || !isTransactionConflict(ex)) {
                    throw ex;
                }
                logger.debug("Transaction conflict; retrying [attempt={}, maxAttempts={}]",
                        attempt, MAX_ATTEMPTS);
                backoff(attempt);
            }
        }
    }

    /**
     * Reports whether a cancellation carries a {@code TransactionConflict} reason.
     *
     * @param ex the transaction cancellation from DynamoDB
     * @return {@code true} when any cancellation reason code is {@code TransactionConflict}
     */
    public static boolean isTransactionConflict(TransactionCanceledException ex) {
        return ex.cancellationReasons().stream()
                .anyMatch(reason -> TRANSACTION_CONFLICT_CODE.equals(reason.code()));
    }

    /**
     * Sleeps for an exponentially growing, jittered interval between retries.
     *
     * @param attempt the attempt number that just failed (1-based)
     */
    private static void backoff(int attempt) {
        long exponential = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS + 1);
        try {
            Thread.sleep(exponential + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }
}

