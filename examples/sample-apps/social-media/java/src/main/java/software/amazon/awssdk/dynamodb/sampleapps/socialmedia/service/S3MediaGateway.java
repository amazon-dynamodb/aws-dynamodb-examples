package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.MediaProperties;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.MediaStorageUnavailableException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Stages and serves media through the standard S3 API.
 *
 * <p>Provides the three object-store operations behind the two-phase presigned flow: mint a
 * presigned {@code PUT} upload URL, confirm an object exists with a {@code HEAD}, and mint a
 * presigned {@code GET} download URL. Presigning does no network I/O, it only signs a request. The
 * {@code HEAD} check is async so it composes with the async publish path.
 *
 * <p>{@link #objectSize} caches a successful or missing-object result for
 * {@link #OBJECT_SIZE_CACHE_TTL} keyed by bucket and key. Consecutive store faults open an
 * in-process circuit breaker that skips {@code HEAD} until {@link #HEAD_BREAKER_OPEN_TTL} elapses.
 * The cache and breaker are process-local and thread-safe for concurrent {@code objectSize} calls.
 *
 * <p>Because access is only through the standard S3 API, the same code path runs against Amazon S3 in
 * the cloud and any S3-compatible store locally. The backend is chosen purely by configuration.
 */
@Component
public class S3MediaGateway {

    private static final Logger logger = LoggerFactory.getLogger(S3MediaGateway.class);

    public static final Duration OBJECT_SIZE_CACHE_TTL = Duration.ofMinutes(1);

    public static final int HEAD_FAILURES_TO_OPEN = 3;

    public static final Duration HEAD_BREAKER_OPEN_TTL = Duration.ofSeconds(30);

    private static final int NOT_FOUND_STATUS = 404;

    private static final String STORE_UNAVAILABLE_MESSAGE = "Media object store is temporarily unavailable";

    private final S3AsyncClient s3AsyncClient;
    private final S3Presigner s3Presigner;
    private final MediaProperties properties;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<CacheKey, CachedSize> objectSizeCache = new ConcurrentHashMap<>();
    private final Object breakerLock = new Object();
    private int consecutiveHeadFailures;
    private long breakerOpenUntilNanos;
    private Throwable lastHeadFailure;

    /**
     * @param s3AsyncClient async S3 client
     * @param s3Presigner   S3 presigner
     * @param properties    media and S3-key configuration
     */
    @Autowired
    public S3MediaGateway(S3AsyncClient s3AsyncClient, S3Presigner s3Presigner, MediaProperties properties) {
        this(s3AsyncClient, s3Presigner, properties, System::nanoTime);
    }

    /**
     * Creates a gateway that reads time from {@code nanoTime}.
     *
     * @param s3AsyncClient async S3 client
     * @param s3Presigner   S3 presigner
     * @param properties    media and S3-key configuration
     * @param nanoTime      monotonic clock used for cache expiry and breaker cooldown
     * @return gateway using {@code nanoTime}
     */
    public static S3MediaGateway withClock(S3AsyncClient s3AsyncClient, S3Presigner s3Presigner,
                                           MediaProperties properties, LongSupplier nanoTime) {
        return new S3MediaGateway(s3AsyncClient, s3Presigner, properties, nanoTime);
    }

    /**
     * @param s3AsyncClient async S3 client
     * @param s3Presigner   S3 presigner
     * @param properties    media and S3-key configuration
     * @param nanoTime      monotonic clock used for cache expiry and breaker cooldown
     */
    private S3MediaGateway(S3AsyncClient s3AsyncClient, S3Presigner s3Presigner, MediaProperties properties,
                           LongSupplier nanoTime) {
        this.s3AsyncClient = s3AsyncClient;
        this.s3Presigner = s3Presigner;
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /**
     * Mints a short-lived presigned {@code PUT} upload URL for a media object.
     *
     * @param bucket      target bucket
     * @param key         object key
     * @param contentType content type the client must upload with
     * @return the presigned upload URL string
     */
    public String presignUpload(String bucket, String key, String contentType) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.presignTtlSeconds()))
                .putObjectRequest(putRequest)
                .build();
        return s3Presigner.presignPutObject(presignRequest).url().toString();
    }

    /**
     * Mints a short-lived presigned {@code GET} download URL for a media object.
     *
     * @param bucket source bucket
     * @param key    object key
     * @return the presigned download URL string
     */
    public String presignDownload(String bucket, String key) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(properties.presignTtlSeconds()))
                .getObjectRequest(getRequest)
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Confirms a media object exists via an S3 {@code HEAD} and returns its size.
     *
     * <p>A successful size or a missing object is cached for {@link #OBJECT_SIZE_CACHE_TTL} under
     * the bucket and key so a publish retry does not repeat {@code HEAD}. After
     * {@link #HEAD_FAILURES_TO_OPEN} consecutive store faults the in-process breaker stays open for
     * {@link #HEAD_BREAKER_OPEN_TTL} and later calls fail with
     * {@link MediaStorageUnavailableException} without issuing {@code HEAD}.
     *
     * @param bucket bucket to check
     * @param key    object key to check
     * @return a future completing with the object size in bytes, or {@code null} when the object is
     *     absent, and completing exceptionally with {@link MediaStorageUnavailableException} on a
     *     transient store fault
     */
    public CompletableFuture<Long> objectSize(String bucket, String key) {
        CacheKey cacheKey = new CacheKey(bucket, key);
        long nowNanos = nanoTime.getAsLong();
        CachedSize cached = objectSizeCache.get(cacheKey);
        if (cached != null && nowNanos < cached.expiresAtNanos()) {
            return CompletableFuture.completedFuture(cached.sizeBytes());
        }
        if (cached != null) {
            objectSizeCache.remove(cacheKey, cached);
        }
        if (isBreakerOpen(nowNanos)) {
            logger.debug("S3 HEAD skipped, circuit breaker is open [bucket={}, key={}]", bucket, key);
            return CompletableFuture.failedFuture(unavailable(lastHeadFailure()));
        }
        return s3AsyncClient.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
                .handle((response, error) -> completeHead(cacheKey, response, error));
    }

    /**
     * Maps a {@code HEAD} outcome onto a cached size, a missing-object {@code null}, or a store-fault
     * exception.
     *
     * @param cacheKey bucket and key for this {@code HEAD}
     * @param response successful {@code HEAD} body, or {@code null} on failure
     * @param error    failure from the async client, or {@code null} on success
     * @return object size in bytes, or {@code null} when the object is absent
     */
    private Long completeHead(CacheKey cacheKey, HeadObjectResponse response, Throwable error) {
        if (error == null) {
            recordHeadSuccess();
            cacheSize(cacheKey, response.contentLength());
            return response.contentLength();
        }
        Throwable cause = unwrap(error);
        if (isNotFound(cause)) {
            recordHeadSuccess();
            cacheSize(cacheKey, null);
            return null;
        }
        logger.warn("S3 HEAD failed, media store may be unavailable [key={}, reason={}]",
                cacheKey.key(), cause.getMessage());
        recordHeadFailure(cause, nanoTime.getAsLong());
        throw unavailable(cause);
    }

    /**
     * Stores a {@code HEAD} result until {@link #OBJECT_SIZE_CACHE_TTL} elapses.
     *
     * @param cacheKey  bucket and key
     * @param sizeBytes object size, or {@code null} when the object is absent
     */
    private void cacheSize(CacheKey cacheKey, Long sizeBytes) {
        long expiresAtNanos = nanoTime.getAsLong() + OBJECT_SIZE_CACHE_TTL.toNanos();
        objectSizeCache.put(cacheKey, new CachedSize(sizeBytes, expiresAtNanos));
    }

    /**
     * @param nowNanos current monotonic time
     * @return {@code true} when {@code HEAD} must be skipped
     */
    private boolean isBreakerOpen(long nowNanos) {
        synchronized (breakerLock) {
            if (breakerOpenUntilNanos == 0L) {
                return false;
            }
            if (nowNanos < breakerOpenUntilNanos) {
                return true;
            }
            breakerOpenUntilNanos = 0L;
            consecutiveHeadFailures = 0;
            return false;
        }
    }

    /**
     * @return the most recent store fault, or {@code null} when none has been recorded
     */
    private Throwable lastHeadFailure() {
        synchronized (breakerLock) {
            return lastHeadFailure;
        }
    }

    /** Clears consecutive {@code HEAD} faults after a reachable store. */
    private void recordHeadSuccess() {
        synchronized (breakerLock) {
            consecutiveHeadFailures = 0;
            breakerOpenUntilNanos = 0L;
        }
    }

    /**
     * Counts a store fault and opens the breaker after {@link #HEAD_FAILURES_TO_OPEN} consecutive
     * failures.
     *
     * @param cause    underlying object-store failure
     * @param nowNanos current monotonic time
     */
    private void recordHeadFailure(Throwable cause, long nowNanos) {
        synchronized (breakerLock) {
            lastHeadFailure = cause;
            consecutiveHeadFailures++;
            if (consecutiveHeadFailures >= HEAD_FAILURES_TO_OPEN) {
                breakerOpenUntilNanos = nowNanos + HEAD_BREAKER_OPEN_TTL.toNanos();
                logger.warn("S3 HEAD circuit breaker opened [consecutiveFailures={}]", consecutiveHeadFailures);
            }
        }
    }

    /**
     * @param cause underlying object-store failure, or {@code null} when the breaker is open
     * @return domain exception mapped to HTTP 503
     */
    private MediaStorageUnavailableException unavailable(Throwable cause) {
        return new MediaStorageUnavailableException(STORE_UNAVAILABLE_MESSAGE, cause);
    }

    /**
     * @param cause unwrapped {@code HEAD} failure
     * @return {@code true} when the object is absent rather than the store being unreachable
     */
    private boolean isNotFound(Throwable cause) {
        if (cause instanceof NoSuchKeyException) {
            return true;
        }
        return cause instanceof S3Exception s3 && s3.statusCode() == NOT_FOUND_STATUS;
    }

    /** Unwraps a {@link CompletionException} wrapper introduced by async composition. */
    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    /**
     * Cache key for one object {@code HEAD} result.
     *
     * @param bucket bucket name
     * @param key    object key
     */
    private record CacheKey(String bucket, String key) {
    }

    /**
     * Cached object size, or {@code null} size when the object was absent, with a monotonic expiry.
     *
     * @param sizeBytes      object size, or {@code null} when the object is absent
     * @param expiresAtNanos monotonic expiry instant
     */
    private record CachedSize(Long sizeBytes, long expiresAtNanos) {
    }
}
