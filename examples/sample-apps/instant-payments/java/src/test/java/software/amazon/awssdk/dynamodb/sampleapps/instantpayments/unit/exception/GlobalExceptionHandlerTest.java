package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.AccountNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler} DynamoDB fault mapping (review item M1).
 *
 * <p>Verifies each DynamoDB service exception receives a distinct, routable error code and the
 * correct status, that throttling/transient faults carry a {@code Retry-After} header, and that a
 * fault wrapped by {@code join()} ({@link CompletionException} or a rethrown {@code RuntimeException})
 * is still unwrapped and mapped rather than collapsing into a generic 500.
 */
@Tag("unit")
public class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleDynamoDbException_whenProvisionedThroughputExceeded_shouldReturn503WithRetryAfter() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDynamoDbException(ProvisionedThroughputExceededException.builder().build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void handleDynamoDbException_whenRequestLimitExceeded_shouldReturn503WithRetryAfter() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDynamoDbException(RequestLimitExceededException.builder().build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("REQUEST_LIMIT_EXCEEDED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void handleDynamoDbException_whenResourceNotFound_shouldReturn503WithTableNotFoundCode() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDynamoDbException(ResourceNotFoundException.builder().build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("TABLE_NOT_FOUND");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void handleDynamoDbException_whenInternalServerError_shouldReturn503WithDistinctCode() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDynamoDbException(InternalServerErrorException.builder().build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("DYNAMODB_INTERNAL_ERROR");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    @Test
    void handleDynamoDbException_whenGenericDynamoDbError_shouldReturn500WithInternalError() {
        ResponseEntity<ErrorResponse> response =
                handler.handleDynamoDbException((DynamoDbException) DynamoDbException.builder().build());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void handleUnexpectedException_whenDynamoDbFaultWrappedInCompletionException_shouldMapDistinctCode() {
        CompletionException wrapped =
                new CompletionException(ProvisionedThroughputExceededException.builder().build());

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
    }

    @Test
    void handleUnexpectedException_whenDynamoDbFaultWrappedInRuntimeException_shouldMapDistinctCode() {
        RuntimeException wrapped = new RuntimeException(
                "Failed to create payment transaction", ResourceNotFoundException.builder().build());

        ResponseEntity<ErrorResponse> response = handler.handleUnexpectedException(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("TABLE_NOT_FOUND");
    }

    @Test
    void handleUnexpectedException_whenNonDynamoDbError_shouldReturnGeneric500() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpectedException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void handleCompletionException_whenAccountNotFoundCause_shouldReturn404() {
        CompletionException wrapped = new CompletionException(new AccountNotFoundException("acc_missing"));

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Account not found: acc_missing");
    }

    @Test
    void handleCompletionException_whenPaymentNotFoundCause_shouldReturn404() {
        CompletionException wrapped = new CompletionException(new PaymentNotFoundException("pay_missing"));

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("Payment not found: pay_missing");
    }

    @Test
    void handleCompletionException_whenDynamoDbFaultCause_shouldMapDistinctCode() {
        CompletionException wrapped =
                new CompletionException(ProvisionedThroughputExceededException.builder().build());

        ResponseEntity<ErrorResponse> response = handler.handleCompletionException(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
    }
}

