package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.FollowerEnumerationRate;

/**
 * In-process permit bucket for {@code PUBLIC} follower-partition Query calls.
 *
 * <p>Both {@link UserGraphRepository} implementations call {@link #queryOrRateLimit} before they
 * issue a Query. A denied call does not Query DynamoDB. It logs {@code WARN} and returns an empty
 * incomplete {@link FollowerQueryResult} so timeline fan-out and notification projection already
 * treat the partition as truncated. This limiter is process-local. It is thread-safe for concurrent
 * {@code queryFollowers} calls.
 */
@Component
public class FollowerEnumerationLimiter {

    private static final double NANOS_PER_SECOND = 1_000_000_000d;

    private final int permitsPerSecond;
    private final LongSupplier nanoTime;
    private double tokens;
    private long lastRefillNanos;

    /**
     * @param rate accepted {@code dynamodb.follower-enumeration-permits-per-second}
     */
    @Autowired
    public FollowerEnumerationLimiter(FollowerEnumerationRate rate) {
        this(rate.value(), System::nanoTime);
    }

    /**
     * @param permitsPerSecond Query permits added each second, also the burst size
     * @param nanoTime         monotonic clock used to refill tokens
     */
    private FollowerEnumerationLimiter(int permitsPerSecond, LongSupplier nanoTime) {
        this.permitsPerSecond = permitsPerSecond;
        this.nanoTime = nanoTime;
        this.tokens = permitsPerSecond;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    /**
     * Creates a limiter that refills from the supplied monotonic clock.
     *
     * @param permitsPerSecond Query permits added each second, also the burst size
     * @param nanoTime         monotonic clock used to refill tokens
     * @return limiter using {@code nanoTime}
     */
    public static FollowerEnumerationLimiter withClock(int permitsPerSecond, LongSupplier nanoTime) {
        return new FollowerEnumerationLimiter(permitsPerSecond, nanoTime);
    }

    /**
     * Runs {@code query} when a permit is available, otherwise skips DynamoDB.
     *
     * @param logger     caller logger
     * @param followeeId creator whose follower partition would be read
     * @param cap        {@code dynamodb.follower-fanout-cap} copied onto a denied result
     * @param query      follower Query, invoked only when a permit is taken
     * @return Query result, or an empty incomplete page when the rate is exceeded
     */
    public CompletableFuture<FollowerQueryResult> queryOrRateLimit(Logger logger, String followeeId, int cap,
                                                                   Supplier<CompletableFuture<FollowerQueryResult>> query) {
        if (tryAcquire()) {
            return query.get();
        }
        logger.warn(
                "PUBLIC follower enumeration rate limited [followeeId={}, permitsPerSecond={}]. Query skipped so the hot partition is not drained. Continue from a cursor with a worker such as SQS, Kinesis, or Step Functions.",
                followeeId, permitsPerSecond);
        return CompletableFuture.completedFuture(new FollowerQueryResult(List.of(), false, cap));
    }

    /**
     * Takes one Query permit when the bucket has a token.
     *
     * @return {@code true} when the caller may Query
     */
    public boolean tryAcquire() {
        synchronized (this) {
            refill();
            if (tokens < 1.0d) {
                return false;
            }
            tokens -= 1.0d;
            return true;
        }
    }

    /** Adds tokens for elapsed time, capped at one second of burst. */
    private void refill() {
        long now = nanoTime.getAsLong();
        double elapsedSeconds = (now - lastRefillNanos) / NANOS_PER_SECOND;
        if (elapsedSeconds > 0) {
            tokens = Math.min(permitsPerSecond, tokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
    }
}
