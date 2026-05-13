package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Configures DynamoDB client beans based on application properties.
 *
 * <p>Required properties (no defaults, fail fast if missing or blank):
 * <ul>
 *   <li>{@code dynamodb.endpoint} (full endpoint URL, for example {@code http://localhost:8000} or
 *       {@code https://dynamodb.eu-west-1.amazonaws.com})</li>
 *   <li>{@code dynamodb.region} (AWS region string, for example {@code eu-west-1})</li>
 *   <li>{@code dynamodb.client-type} ({@code high-level} for enhanced client or {@code low-level})</li>
 * </ul>
 *
 * <p>When the endpoint host is local ({@code localhost}, {@code dynamodb}, etc.), fake credentials
 * are used. Otherwise, the default AWS credential chain is used.
 *
 * <p>The low-level {@link DynamoDbAsyncClient} is always created for table management and serves
 * as the foundation for the enhanced client.
 */
@Configuration
public class DynamoDbConfig {

    /** DynamoDB endpoint URL from {@code dynamodb.endpoint}. */
    @Value("${dynamodb.endpoint:}")
    private String endpoint;

    /** AWS region string from {@code dynamodb.region}. */
    @Value("${dynamodb.region:}")
    private String region;

    /** Client flavour from {@code dynamodb.client-type} ({@code high-level} or {@code low-level}). */
    @Value("${dynamodb.client-type:}")
    private String clientType;

    /**
     * Fails fast at startup when required {@code dynamodb.*} properties are missing or blank.
     */
    @PostConstruct
    void validate() {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("dynamodb.endpoint is required");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException("dynamodb.region is required");
        }
        if (clientType == null || clientType.isBlank()) {
            throw new IllegalStateException("dynamodb.client-type is required");
        }
    }

    /**
     * Creates the low-level async DynamoDB client with an explicit retry strategy.
     *
     * <p>Uses a custom endpoint and fake credentials when connecting to local DynamoDB;
     * otherwise uses the default AWS credential chain.
     *
     * <p><strong>Retry strategy.</strong> DynamoDB's SDK-level retry handles transient failures such as
     * {@code ProvisionedThroughputExceededException} (total request failure), 5xx service errors,
     * and I/O timeouts. The configuration below mirrors DynamoDB's built-in defaults but makes every
     * knob explicit so operators can tune them for their workload:
     * <ul>
     *   <li>{@code maxAttempts(9)} (one initial call plus up to eight retries, DynamoDB default)</li>
     *   <li>{@code backoffStrategy} (full-jitter exponential delay with 25 ms base and 20 s cap)</li>
     *   <li>{@code throttlingBackoffStrategy} (same shape with 1 s base for throttle errors so
     *       DynamoDB can recover capacity)</li>
     * </ul>
     *
     * <p><strong>Unprocessed keys.</strong> SDK retries do not cover {@code UnprocessedKeys} from
     * {@code BatchGetItem} because that response is still HTTP 200 (partial success). Application-level
     * retry for those keys is implemented on {@link PaymentRepository#batchGetReservations(String, List)}.
     *
     * @see BackoffStrategy#exponentialDelay(Duration, Duration)
     */
    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient() {
        var builder = DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .overrideConfiguration(o -> o.retryStrategy(retry -> retry
                        .maxAttempts(9)
                        .backoffStrategy(BackoffStrategy.exponentialDelay(
                                Duration.ofMillis(25), Duration.ofSeconds(20)))
                        .throttlingBackoffStrategy(BackoffStrategy.exponentialDelay(
                                Duration.ofSeconds(1), Duration.ofSeconds(20)))));

        if (DynamoDbEndpointUtils.isLocalEndpoint(endpoint)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")));
        }

        return builder.build();
    }

    /**
     * Creates the high-level enhanced async DynamoDB client, wrapping the low-level client.
     *
     * <p>Only instantiated when {@code dynamodb.client-type=high-level}.
     */
    @Bean
    @ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
    public DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return DynamoDbEnhancedAsyncClient.builder()
                .dynamoDbClient(dynamoDbAsyncClient)
                .extensions(VersionedRecordExtension.builder().build())
                .build();
    }
}
