package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.dto.ErrorResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

/**
 * Unit coverage for domain and DynamoDB classification in {@link ExceptionMapper}.
 *
 * <p>A dedicated domain type and a DynamoDB throttle must resolve to the same status and error code
 * as the advice class. Unmapped types return empty so the generic fallback can run. No Docker or
 * Spring context is required.
 */
@Tag("unit")
class ExceptionMapperTest {

    private final ExceptionMapper mapper = new ExceptionMapper();

    @Test
    void map_whenDomainTypeIsUserNotFound_returnsNotFoundWithUserNotFoundCode() {
        UserNotFoundException exception = new UserNotFoundException("user_ghost");

        ResponseEntity<ErrorResponse> response = mapper.map(exception).orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("USER_NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("User not found: user_ghost");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
    }

    @Test
    void map_whenCauseIsDynamoDbThrottle_returnsServiceUnavailableWithThroughputExceeded() {
        ProvisionedThroughputExceededException exception = ProvisionedThroughputExceededException.builder()
                .message("throttled-secret")
                .build();

        ResponseEntity<ErrorResponse> response = mapper.map(exception).orElseThrow();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("THROUGHPUT_EXCEEDED");
        assertThat(response.getBody().message())
                .isEqualTo("Request rate exceeded provisioned throughput. Retry after a short delay");
        assertThat(response.getBody().message()).doesNotContain("Reference:");
        assertThat(response.getBody().message()).doesNotContain("throttled-secret");
    }

    @Test
    void map_whenTypeIsUnmapped_returnsEmpty() {
        assertThat(mapper.map(new IllegalStateException("unmapped"))).isEmpty();
    }
}
