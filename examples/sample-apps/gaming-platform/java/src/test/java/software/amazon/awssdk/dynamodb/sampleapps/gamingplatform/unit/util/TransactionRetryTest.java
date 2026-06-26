package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.TransactionRetry;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Unit tests for {@link TransactionRetry}, covering conflict detection and the retry loop.
 */
@Tag("unit")
class TransactionRetryTest {

    @Test
    void runWithConflictRetry_whenConflictThenSuccess_shouldRetryAndReturn() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransactionRetry.runWithConflictRetry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw conflict();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void runWithConflictRetry_whenConflictPersists_shouldExhaustAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetry(() -> {
            attempts.incrementAndGet();
            throw conflict();
        })).isInstanceOf(TransactionCanceledException.class);

        assertThat(attempts.get()).isEqualTo(TransactionRetry.MAX_ATTEMPTS);
    }

    @Test
    void runWithConflictRetry_whenConditionalCheckFailed_shouldNotRetry() {
        AtomicInteger attempts = new AtomicInteger();

        TransactionCanceledException notConflict = TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build();

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetry(() -> {
            attempts.incrementAndGet();
            throw notConflict;
        })).isInstanceOf(TransactionCanceledException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void runWithConflictRetry_whenSuccessFirstAttempt_shouldNotRetry() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransactionRetry.runWithConflictRetry(() -> {
            attempts.incrementAndGet();
            return "value";
        });

        assertThat(result).isEqualTo("value");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void isTransactionConflict_whenMixedReasons_shouldDetectConflict() {
        TransactionCanceledException mixed = TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(
                        CancellationReason.builder().code("ConditionalCheckFailed").build(),
                        CancellationReason.builder().code("TransactionConflict").build())
                .build();

        assertThat(TransactionRetry.isTransactionConflict(mixed)).isTrue();
    }

    @Test
    void runWithConflictRetryAsync_whenSuccessFirstAttempt_shouldNotRetry() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransactionRetry.runWithConflictRetryAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture("value");
        }).join();

        assertThat(result).isEqualTo("value");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void runWithConflictRetryAsync_whenConflictThenSuccess_shouldRetryAndReturn() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransactionRetry.runWithConflictRetryAsync(() -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(conflict());
            }
            return CompletableFuture.completedFuture("ok");
        }).join();

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void runWithConflictRetryAsync_whenConflictPersists_shouldExhaustAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetryAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.<String>failedFuture(conflict());
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TransactionCanceledException.class);

        assertThat(attempts.get()).isEqualTo(TransactionRetry.MAX_ATTEMPTS);
    }

    @Test
    void runWithConflictRetryAsync_whenConditionalCheckFailed_shouldNotRetry() {
        AtomicInteger attempts = new AtomicInteger();
        TransactionCanceledException notConflict = TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build();

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetryAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.<String>failedFuture(notConflict);
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TransactionCanceledException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void runWithConflictRetryAsync_whenConflictWrappedInCompletionException_shouldStillRetry() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransactionRetry.runWithConflictRetryAsync(() -> {
            if (attempts.incrementAndGet() == 1) {
                // Composition layers can wrap the cancellation; the async retry must unwrap it.
                return CompletableFuture.failedFuture(new CompletionException(conflict()));
            }
            return CompletableFuture.completedFuture("ok");
        }).join();

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void runWithConflictRetryAsync_whenSupplierThrowsSynchronously_shouldCompleteExceptionally() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("supplier boom");

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetryAsync(() -> {
            attempts.incrementAndGet();
            throw boom;
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(boom);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void runWithConflictRetryAsync_whenNonConflictFailure_shouldNotRetryAndPropagate() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException dependencyFailure = new RuntimeException("dependency down");

        assertThatThrownBy(() -> TransactionRetry.runWithConflictRetryAsync(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.<String>failedFuture(dependencyFailure);
        }).join())
                .isInstanceOf(CompletionException.class)
                .hasCause(dependencyFailure);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void unwrap_whenNestedCompletionException_shouldReturnRootCause() {
        TransactionCanceledException root = conflict();
        Throwable nested = new CompletionException(new CompletionException(root));

        assertThat(TransactionRetry.unwrap(nested)).isSameAs(root);
    }

    @Test
    void unwrap_whenPlainThrowable_shouldReturnSameInstance() {
        RuntimeException plain = new RuntimeException("plain");

        assertThat(TransactionRetry.unwrap(plain)).isSameAs(plain);
    }

    @Test
    void unwrap_whenCompletionExceptionWithoutCause_shouldReturnSameInstance() {
        CompletionException noCause = new CompletionException((Throwable) null);

        assertThat(TransactionRetry.unwrap(noCause)).isSameAs(noCause);
    }

    /**
     * Builds a cancellation carrying a single {@code TransactionConflict} reason.
     *
     * @return a conflict cancellation
     */
    private static TransactionCanceledException conflict() {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(CancellationReason.builder().code("TransactionConflict").build())
                .build();
    }
}
