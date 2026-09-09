package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.AsyncDelayConfig;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;

/**
 * Small helpers for composing {@link CompletableFuture} chains without blocking servlet threads.
 *
 * <p>Delay backoffs use a dedicated {@code async-delay-*} {@link ScheduledExecutorService} rather than
 * {@code CompletableFuture.runAsync()} on the common ForkJoin pool, so retry sleeps in
 * {@link OutboundPaymentProcessor} do not compete with unrelated parallel work elsewhere in the JVM.
 */
@Component
public class AsyncSupport {

    /** Bounded scheduler from {@link AsyncDelayConfig} used by {@link #sleepMillis(long)}. */
    private final ScheduledExecutorService delayScheduler;

    /**
     * @param delayScheduler bounded scheduler from {@link AsyncDelayConfig}
     */
    public AsyncSupport(@Qualifier(AsyncDelayConfig.ASYNC_DELAY_SCHEDULER_BEAN) ScheduledExecutorService delayScheduler) {
        this.delayScheduler = delayScheduler;
    }

    /**
     * Returns a future that completes after {@code millis} on the dedicated delay scheduler.
     *
     * @param millis sleep duration
     * @return completed void future after the delay
     */
    public CompletableFuture<Void> sleepMillis(long millis) {
        if (millis <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> delayed = new CompletableFuture<>();
        delayScheduler.schedule(() -> delayed.complete(null), millis, TimeUnit.MILLISECONDS);
        return delayed;
    }

    /**
     * Unwraps a {@link CompletionException} cause when present.
     *
     * @param throwable raw throwable from a future
     * @return unwrapped cause or the original throwable
     */
    public static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException
                && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return throwable;
    }

    /**
     * Async error recovery equivalent to {@code exceptionallyCompose} for environments that prefer
     * an explicit helper.
     *
     * @param future   source future
     * @param recovery maps the exceptional completion to a replacement future
     * @param <T>      result type
     * @return composed future
     */
    public static <T> CompletableFuture<T> exceptionallyCompose(CompletableFuture<T> future,
                                                                Function<Throwable, CompletableFuture<T>> recovery) {
        return future.handle((value, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(value);
            }
            return recovery.apply(error);
        }).thenCompose(Function.identity());
    }
}
