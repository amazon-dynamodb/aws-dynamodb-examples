package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>Two flavors are provided:
 * <ul>
 *   <li>{@link #runWithConflictRetry(Supplier)} blocks the calling thread and sleeps between attempts.
 *       Used by background jobs that already run off the request path.</li>
 *   <li>{@link #runWithConflictRetryAsync(Supplier)} composes {@link CompletableFuture} stages and
 *       schedules the backoff on a shared timer instead of sleeping, so request threads stay free
 *       end to end. Used by the request-path services.</li>
 * </ul>
 */
public final class TransactionRetry {

    private static final Logger logger = LoggerFactory.getLogger(TransactionRetry.class);

    /** Maximum number of attempts, including the first, before a conflict propagates. */
    public static final int MAX_ATTEMPTS = 3;

    /** DynamoDB cancellation reason code for a serializable transaction conflict. */
    static final String TRANSACTION_CONFLICT_CODE = "TransactionConflict";

    /** Base backoff in milliseconds, grown exponentially per attempt and jittered. */
    private static final long BASE_BACKOFF_MS = 25;

    /**
     * Shared single-thread timer that schedules the async backoff delay. Daemon so it never blocks JVM
     * shutdown. It only sleeps a retry, the actual work runs on the SDK's completion threads.
     */
    private static final ScheduledExecutorService RETRY_SCHEDULER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "txn-retry-backoff");
        thread.setDaemon(true);
        return thread;
    });

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
                logger.debug("Transaction conflict, retrying [attempt={}, maxAttempts={}]",
                        attempt, MAX_ATTEMPTS);
                backoff(attempt);
            }
        }
    }

    /**
     * Asynchronous counterpart of {@link #runWithConflictRetry(Supplier)} for the request path.
     *
     * <p>Runs {@code operation} and, when its future completes with a {@code TransactionConflict}
     * cancellation, schedules a re-run after a jittered exponential delay on {@link #RETRY_SCHEDULER}
     * rather than sleeping a worker thread. A non-conflict outcome (success, domain exception,
     * idempotent replay, or a {@code ConditionalCheckFailed} cancellation) completes the returned
     * future immediately. After {@link #MAX_ATTEMPTS} the last conflict propagates.
     *
     * @param operation supplies the read, build, and transact unit as a {@link CompletableFuture}
     * @param <T>       the operation result type
     * @return a future completing with the first successful attempt, or exceptionally on the final conflict
     */
    public static <T> CompletableFuture<T> runWithConflictRetryAsync(Supplier<CompletableFuture<T>> operation) {
        return attemptAsync(operation, 1);
    }

    private static <T> CompletableFuture<T> attemptAsync(Supplier<CompletableFuture<T>> operation, int attempt) {
        CompletableFuture<T> result = new CompletableFuture<>();
        invokeSafely(operation).whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
                return;
            }
            Throwable cause = unwrap(error);
            if (cause instanceof TransactionCanceledException tce
                    && attempt < MAX_ATTEMPTS && isTransactionConflict(tce)) {
                logger.debug("Transaction conflict, retrying [attempt={}, maxAttempts={}]",
                        attempt, MAX_ATTEMPTS);
                RETRY_SCHEDULER.schedule(
                        () -> attemptAsync(operation, attempt + 1).whenComplete((retryValue, retryError) -> {
                            if (retryError == null) {
                                result.complete(retryValue);
                            } else {
                                result.completeExceptionally(unwrap(retryError));
                            }
                        }),
                        backoffMillis(attempt), TimeUnit.MILLISECONDS);
            } else {
                result.completeExceptionally(cause);
            }
        });
        return result;
    }

    /**
     * Invokes the supplier, turning a synchronous throw into a failed future so the retry logic only
     * has to inspect a single completion path.
     *
     * @param operation the operation supplier
     * @param <T>       the operation result type
     * @return the operation's future, or a failed future if the supplier threw synchronously
     */
    private static <T> CompletableFuture<T> invokeSafely(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
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
     * Unwraps {@link CompletionException} layers added by {@link CompletableFuture} composition so the
     * underlying DynamoDB cause (for example {@link TransactionCanceledException}) is exposed.
     *
     * @param throwable the throwable reported by a completion stage
     * @return the first non-{@link CompletionException} cause, or the input when there is none
     */
    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Computes the jittered exponential backoff delay for an attempt without sleeping.
     *
     * @param attempt the attempt number that just failed (1-based)
     * @return the delay in milliseconds before the next attempt
     */
    private static long backoffMillis(int attempt) {
        long exponential = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS + 1);
        return exponential + jitter;
    }

    /**
     * Sleeps for an exponentially growing, jittered interval between retries.
     *
     * @param attempt the attempt number that just failed (1-based)
     */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(backoffMillis(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        }
    }
}
