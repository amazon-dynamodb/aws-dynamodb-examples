package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.MediaProperties;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.MediaStorageUnavailableException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.service.S3MediaGateway;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Unit coverage for {@code HEAD} cache and circuit-breaker behaviour in {@link S3MediaGateway}.
 *
 * <p>A frozen clock proves a cache hit, cache expiry, not-found caching, store faults, and the open
 * breaker path without waiting. No object store or Spring context is required.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class S3MediaGatewayTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "media/user_alice/media_1";
    private static final long SIZE_BYTES = 482_310L;

    @Mock
    private S3AsyncClient s3AsyncClient;

    @Mock
    private S3Presigner s3Presigner;

    private AtomicLong nanos;
    private S3MediaGateway gateway;

    @BeforeEach
    void setUp() {
        nanos = new AtomicLong();
        gateway = S3MediaGateway.withClock(s3AsyncClient, s3Presigner, mediaProperties(), nanos::get);
    }

    @Test
    void objectSize_whenObjectPresent_returnsContentLength() {
        stubHeadSuccess(SIZE_BYTES);

        Long size = gateway.objectSize(BUCKET, KEY).join();

        assertThat(size).isEqualTo(SIZE_BYTES);
        HeadObjectRequest request = capturedHeadRequest();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(KEY);
    }

    @Test
    void objectSize_whenCached_doesNotInvokeHeadObject() {
        stubHeadSuccess(SIZE_BYTES);

        assertThat(gateway.objectSize(BUCKET, KEY).join()).isEqualTo(SIZE_BYTES);
        assertThat(gateway.objectSize(BUCKET, KEY).join()).isEqualTo(SIZE_BYTES);

        verify(s3AsyncClient, times(1)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void objectSize_whenCacheTtlElapses_invokesHeadObjectAgain() {
        stubHeadSuccess(SIZE_BYTES);
        gateway.objectSize(BUCKET, KEY).join();

        nanos.set(S3MediaGateway.OBJECT_SIZE_CACHE_TTL.toNanos());
        gateway.objectSize(BUCKET, KEY).join();

        verify(s3AsyncClient, times(2)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void objectSize_whenNoSuchKey_returnsNull() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().message("missing").build()));

        assertThat(gateway.objectSize(BUCKET, KEY).join()).isNull();
    }

    @Test
    void objectSize_whenMissingObjectCached_doesNotInvokeHeadObject() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(NoSuchKeyException.builder().message("missing").build()));

        assertThat(gateway.objectSize(BUCKET, KEY).join()).isNull();
        assertThat(gateway.objectSize(BUCKET, KEY).join()).isNull();

        verify(s3AsyncClient, times(1)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void objectSize_whenS3StatusIs404_returnsNull() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(s3Exception(404, "Not Found")));

        assertThat(gateway.objectSize(BUCKET, KEY).join()).isNull();
    }

    @Test
    void objectSize_whenStoreFault_throwsMediaStorageUnavailable() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(s3Exception(500, "Internal Error")));

        assertThatThrownBy(() -> gateway.objectSize(BUCKET, KEY).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MediaStorageUnavailableException.class)
                .hasRootCauseInstanceOf(S3Exception.class);
    }

    @Test
    void objectSize_whenBreakerOpen_doesNotInvokeHeadObject() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(s3Exception(500, "Internal Error")));
        for (int i = 0; i < S3MediaGateway.HEAD_FAILURES_TO_OPEN; i++) {
            assertThatThrownBy(() -> gateway.objectSize(BUCKET, KEY).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(MediaStorageUnavailableException.class);
        }

        assertThatThrownBy(() -> gateway.objectSize(BUCKET, KEY).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MediaStorageUnavailableException.class);

        verify(s3AsyncClient, times(S3MediaGateway.HEAD_FAILURES_TO_OPEN)).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void objectSize_whenBreakerCooldownElapses_invokesHeadObjectAgain() {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(s3Exception(500, "Internal Error")));
        for (int i = 0; i < S3MediaGateway.HEAD_FAILURES_TO_OPEN; i++) {
            assertThatThrownBy(() -> gateway.objectSize(BUCKET, KEY).join())
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(MediaStorageUnavailableException.class);
        }

        nanos.set(S3MediaGateway.HEAD_BREAKER_OPEN_TTL.toNanos());
        assertThatThrownBy(() -> gateway.objectSize(BUCKET, KEY).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(MediaStorageUnavailableException.class);

        verify(s3AsyncClient, times(S3MediaGateway.HEAD_FAILURES_TO_OPEN + 1))
                .headObject(any(HeadObjectRequest.class));
    }

    @Test
    void objectSize_whenDifferentKey_invokesHeadObjectAgain() {
        stubHeadSuccess(SIZE_BYTES);

        gateway.objectSize(BUCKET, KEY).join();
        gateway.objectSize(BUCKET, "media/user_alice/media_2").join();

        verify(s3AsyncClient, times(2)).headObject(any(HeadObjectRequest.class));
    }

    /** Stubs a successful {@code HEAD} with the given content length. */
    private void stubHeadSuccess(long sizeBytes) {
        when(s3AsyncClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        HeadObjectResponse.builder().contentLength(sizeBytes).build()));
    }

    /** @return the single captured {@code HEAD} request */
    private HeadObjectRequest capturedHeadRequest() {
        ArgumentCaptor<HeadObjectRequest> captor = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3AsyncClient).headObject(captor.capture());
        return captor.getValue();
    }

    /**
     * @param statusCode HTTP status on the S3 error
     * @param message    error message
     * @return an {@link S3Exception} with that status
     */
    private static S3Exception s3Exception(int statusCode, String message) {
        return (S3Exception) S3Exception.builder().statusCode(statusCode).message(message).build();
    }

    /** @return media properties used only to construct the gateway */
    private static MediaProperties mediaProperties() {
        return new MediaProperties(
                BUCKET, "media/", 900, 3,
                List.of("image/jpeg"), List.of("video/mp4"),
                10_485_760L, 104_857_600L);
    }
}
