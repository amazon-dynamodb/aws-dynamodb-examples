package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.FollowerEnumerationLimiter;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.FollowerQueryResult;

/**
 * Unit coverage for the in-process follower enumeration permit bucket.
 *
 * <p>A frozen clock proves allow then deny without waiting. Advancing the clock by one second
 * refills a permit. A denied {@code queryOrRateLimit} must not invoke the Query supplier.
 */
@Tag("unit")
class FollowerEnumerationLimiterTest {

    @Test
    void tryAcquire_whenFirstPermit_returnsTrue() {
        FollowerEnumerationLimiter limiter = FollowerEnumerationLimiter.withClock(1, () -> 0L);

        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void tryAcquire_whenBurstExhausted_returnsFalse() {
        FollowerEnumerationLimiter limiter = FollowerEnumerationLimiter.withClock(1, () -> 0L);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void tryAcquire_whenOneSecondElapses_refillsAPermit() {
        AtomicLong nanos = new AtomicLong();
        FollowerEnumerationLimiter limiter = FollowerEnumerationLimiter.withClock(1, nanos::get);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        nanos.set(1_000_000_000L);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void queryOrRateLimit_whenPermitAvailable_runsQuery() {
        FollowerEnumerationLimiter limiter = FollowerEnumerationLimiter.withClock(1, () -> 0L);
        FollowerQueryResult page = new FollowerQueryResult(List.of());
        @SuppressWarnings("unchecked")
        Supplier<CompletableFuture<FollowerQueryResult>> query = mock(Supplier.class);
        when(query.get()).thenReturn(CompletableFuture.completedFuture(page));
        Logger logger = mock(Logger.class);

        FollowerQueryResult result = limiter.queryOrRateLimit(logger, "user_author", 2, query).join();

        assertThat(result).isSameAs(page);
        verify(query).get();
        verify(logger, never()).warn(
                "PUBLIC follower enumeration rate limited [followeeId={}, permitsPerSecond={}]. Query skipped so the hot partition is not drained. Continue from a cursor with a worker such as SQS, Kinesis, or Step Functions.",
                "user_author", 1);
    }

    @Test
    void queryOrRateLimit_whenDenied_skipsQueryAndReturnsIncomplete() {
        FollowerEnumerationLimiter limiter = FollowerEnumerationLimiter.withClock(1, () -> 0L);
        limiter.tryAcquire();
        @SuppressWarnings("unchecked")
        Supplier<CompletableFuture<FollowerQueryResult>> query = mock(Supplier.class);
        Logger logger = mock(Logger.class);

        FollowerQueryResult result = limiter.queryOrRateLimit(logger, "user_author", 2, query).join();

        assertThat(result.complete()).isFalse();
        assertThat(result.followers()).isEmpty();
        assertThat(result.cap()).isEqualTo(2);
        verify(query, never()).get();
        verify(logger).warn(
                "PUBLIC follower enumeration rate limited [followeeId={}, permitsPerSecond={}]. Query skipped so the hot partition is not drained. Continue from a cursor with a worker such as SQS, Kinesis, or Step Functions.",
                "user_author", 1);
    }
}
