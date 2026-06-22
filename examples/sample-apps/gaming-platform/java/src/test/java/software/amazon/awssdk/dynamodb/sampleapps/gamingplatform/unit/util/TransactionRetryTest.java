package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

