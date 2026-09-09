package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.MissingActorException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.UserNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.LowLevelDynamoDbContentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.RequestReference;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

/**
 * Unit coverage for unexpected versus mapped error envelopes.
 *
 * <p>The generic {@code INTERNAL_ERROR} path must include the request reference id and must not leak
 * the cause text. Mapped domain and DynamoDB responses keep their existing message shape. A stubbed
 * {@code DynamoDbAsyncClient} that fails with {@code ApiCallTimeoutException} or
 * {@code SdkClientException} is unmapped, so async completion uses that generic envelope. No Docker
 * or Spring context is required.
 */
@Tag("unit")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Clears MDC so a leftover request id cannot leak into the next test.
     */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void handleUnexpectedException_whenCauseIsGeneric_includesRequestReferenceAndHidesCause() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(
                new IllegalStateException("secret-cause-text"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred. Reference: req_support_123");
        assertThat(response.getBody().message()).doesNotContain("secret-cause-text");
        assertThat(response.getBody().message()).doesNotContain("IllegalStateException");
    }

    @Test
    void handleMissingActor_whenMappedDomainError_keepsOriginalMessage() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        MissingActorException exception = new MissingActorException();

        ResponseEntity<ErrorResponse> response = handler.handleMissingActor(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo(exception.getMessage());
        assertThat(response.getBody().message()).doesNotContain("Reference:");
    }

    @Test
    void handleUserNotFound_whenMappedDomainError_keepsOriginalMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleUserNotFound(new UserNotFoundException("user_ghost"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("USER_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("User not found: user_ghost");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
    }

    @Test
    void handleUnexpectedException_whenCauseIsDynamoDbThrottle_keepsMappedMessageWithoutReference() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder()
                .message("throttled-secret")
                .build();

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
        assertThat(response.getBody().message())
                .isEqualTo("Request rate exceeded provisioned throughput. Retry after a short delay");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
        assertThat(response.getBody().message()).doesNotContain("throttled-secret");
    }

    @Test
    void handleUnexpectedException_whenCauseIsUnhandledDynamoDb_keepsInternalErrorWithoutReference() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        DynamoDbException exception = (DynamoDbException) DynamoDbException.builder().message("ddb-secret").build();

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
        assertThat(response.getBody().message()).doesNotContain("ddb-secret");
    }

    @Test
    void handleCompletionException_whenCauseIsMappedDomain_keepsOriginalMessage() {
        UserNotFoundException cause = new UserNotFoundException("user_ghost");

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(new CompletionException(cause));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("USER_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("User not found: user_ghost");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
    }

    @Test
    void handleCompletionException_whenCauseIsDynamoDbThrottle_keepsMappedMessageWithoutReference() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        ProvisionedThroughputExceededException cause = ProvisionedThroughputExceededException.builder()
                .message("throttled-secret")
                .build();

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(new CompletionException(cause));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
        assertThat(response.getBody().message())
                .isEqualTo("Request rate exceeded provisioned throughput. Retry after a short delay");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
        assertThat(response.getBody().message()).doesNotContain("throttled-secret");
    }

    @Test
    void handleCompletionException_whenCauseIsUnmapped_includesRequestReferenceAndHidesCause() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(
                new CompletionException(new IllegalStateException("secret-cause-text")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred. Reference: req_support_123");
        assertThat(response.getBody().message()).doesNotContain("secret-cause-text");
        assertThat(response.getBody().message()).doesNotContain("IllegalStateException");
    }

    @Test
    void handleCompletionException_whenDynamoDbClientThrowsApiCallTimeout_includesRequestReferenceAndHidesCause() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        ApiCallTimeoutException timeout = ApiCallTimeoutException.builder()
                .message("call-timed-out-secret")
                .build();

        ResponseEntity<ErrorResponse> response = envelopeForClientFault(timeout);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred. Reference: req_support_123");
        assertThat(response.getBody().message()).doesNotContain("call-timed-out-secret");
        assertThat(response.getBody().message()).doesNotContain("ApiCallTimeoutException");
    }

    @Test
    void handleCompletionException_whenDynamoDbClientThrowsSdkClientException_includesRequestReferenceAndHidesCause() {
        MDC.put(RequestReference.MDC_KEY, "req_support_123");
        SdkClientException fault = SdkClientException.create("connection-reset-secret");

        ResponseEntity<ErrorResponse> response = envelopeForClientFault(fault);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred. Reference: req_support_123");
        assertThat(response.getBody().message()).doesNotContain("connection-reset-secret");
        assertThat(response.getBody().message()).doesNotContain("SdkClientException");
    }

    /**
     * Stubs {@code getItem} to fail with {@code fault}, runs a low-level Content read, and maps the
     * async completion through the advice class.
     *
     * @param fault SDK client fault returned by the stubbed DynamoDB client
     * @return mapped error envelope
     */
    private ResponseEntity<ErrorResponse> envelopeForClientFault(RuntimeException fault) {
        DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.failedFuture(fault));
        LowLevelDynamoDbContentRepository repository =
                new LowLevelDynamoDbContentRepository(client, "JavaContent");
        Throwable thrown = catchThrowable(() -> repository.getPostMeta("post_1").join());
        assertThat(thrown).isInstanceOf(CompletionException.class);
        return handler.handleCompletionException((CompletionException) thrown);
    }
}
