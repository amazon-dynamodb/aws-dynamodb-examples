package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.AsyncSupport;

/**
 * Unit tests for {@link AsyncSupport}.
 */
@Tag("unit")
public class AsyncSupportTest {

    private ScheduledExecutorService delayScheduler;
    private AsyncSupport asyncSupport;

    /**
     * Creates a single-threaded daemon scheduler and the {@link AsyncSupport} under test.
     */
    @BeforeEach
    void setUp() {
        delayScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "async-delay-test");
            thread.setDaemon(true);
            return thread;
        });
        asyncSupport = new AsyncSupport(delayScheduler);
    }

    /**
     * Stops the scheduler so test threads do not leak between cases.
     */
    @AfterEach
    void tearDown() {
        delayScheduler.shutdownNow();
    }

    @Test
    void sleepMillis_whenZeroOrNegative_shouldCompleteImmediately() {
        long startNanos = System.nanoTime();

        asyncSupport.sleepMillis(0).join();
        asyncSupport.sleepMillis(-5).join();

        assertThat(System.nanoTime() - startNanos)
                .isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
    }

    @Test
    void sleepMillis_whenPositive_shouldCompleteAfterScheduledDelay() {
        long startNanos = System.nanoTime();

        asyncSupport.sleepMillis(30).join();

        assertThat(System.nanoTime() - startNanos)
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(25));
    }

    @Test
    void unwrap_whenCompletionExceptionWithCause_shouldReturnCause() {
        IllegalStateException cause = new IllegalStateException("boom");
        CompletionException wrapped = new CompletionException(cause);

        assertThat(AsyncSupport.unwrap(wrapped)).isSameAs(cause);
    }

    @Test
    void unwrap_whenPlainThrowable_shouldReturnSameInstance() {
        RuntimeException error = new RuntimeException("plain");

        assertThat(AsyncSupport.unwrap(error)).isSameAs(error);
    }

    @Test
    void exceptionallyCompose_whenSourceCompletesNormally_shouldReturnValue() {
        CompletableFuture<String> result = AsyncSupport.exceptionallyCompose(
                CompletableFuture.completedFuture("ok"),
                error -> CompletableFuture.failedFuture(error));

        assertThat(result.join()).isEqualTo("ok");
    }

    @Test
    void exceptionallyCompose_whenSourceFails_shouldRunRecovery() {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        RuntimeException failure = new RuntimeException("source failed");

        CompletableFuture<String> result = AsyncSupport.exceptionallyCompose(
                CompletableFuture.failedFuture(failure),
                error -> {
                    captured.set(error);
                    return CompletableFuture.completedFuture("recovered");
                });

        assertThat(result.join()).isEqualTo("recovered");
        assertThat(captured.get()).isSameAs(failure);
    }

    @Test
    void exceptionallyCompose_whenRecoveryFails_shouldPropagateRecoveryFailure() {
        RuntimeException sourceFailure = new RuntimeException("source failed");
        IllegalStateException recoveryFailure = new IllegalStateException("recovery failed");

        CompletableFuture<String> result = AsyncSupport.exceptionallyCompose(
                CompletableFuture.failedFuture(sourceFailure),
                error -> CompletableFuture.failedFuture(recoveryFailure));

        assertThatThrownBy(result::join)
                .isInstanceOf(CompletionException.class)
                .satisfies(ex -> assertThat(ex.getCause()).isSameAs(recoveryFailure));
    }
}
