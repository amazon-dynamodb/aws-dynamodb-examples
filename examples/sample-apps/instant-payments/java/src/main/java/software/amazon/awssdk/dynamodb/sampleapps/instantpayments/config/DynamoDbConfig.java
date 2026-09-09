package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.IdempotencyRecord;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.repository.PaymentRepository;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
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
 * <p>Optional properties with validated defaults:
 * <ul>
 *   <li>{@code dynamodb.idempotency-ttl-seconds}: default {@code 2592000}, validated between
 *       {@link #MIN_IDEMPOTENCY_TTL_SECONDS} and {@link #MAX_IDEMPOTENCY_TTL_SECONDS} inclusive</li>
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

    /**
     * Per-attempt timeout. A single network attempt (the first call or any one retry) that has not
     * received a response within this budget is abandoned and handed back to the retry strategy, so
     * one slow connection never pins a request thread waiting on a reply.
     */
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofMillis(1500);

    /**
     * End-to-end call timeout across all retries. The whole operation ({@code GetItem},
     * {@code TransactWriteItems}, etc.) gives up once this elapses and returns a timeout to the caller,
     * so a payment request fails fast with a clear error instead of hanging. This overall budget also
     * caps how many of the configured retry attempts can actually run.
     */
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Maximum number of concurrent connections the async (Netty) HTTP client keeps open to DynamoDB.
     * The SDK default is 50. HTTP handlers compose repository futures without blocking Tomcat worker
     * threads ({@linkplain WebMvcAsyncConfig async MVC}), so in-flight HTTP work is not capped by
     * {@code min(Tomcat workers, this value)} alone.
     * Sizing this pool generously avoids the Netty client becoming the limiter before DynamoDB under burst load.
     */
    static final int HTTP_MAX_CONCURRENCY = 200;

    /**
     * How long a caller waits for a connection to free up from the pool before failing. The SDK default
     * is 10 s, which under burst load adds a long latency tail and outlives the call budget. This value
     * is kept coherent with {@link #API_CALL_ATTEMPT_TIMEOUT}: an attempt is abandoned at 1.5 s, so the
     * 2 s acquisition wait is a backstop that fails fast on a saturated pool rather than queuing for 10 s.
     */
    static final Duration HTTP_CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Minimum allowed idempotency TTL configuration: one hour (seconds).
     */
    static final long MIN_IDEMPOTENCY_TTL_SECONDS = 3_600L;

    /**
     * Maximum allowed idempotency TTL configuration: {@code 31_536_000} seconds (365 x 24 x 3,600).
     */
    static final long MAX_IDEMPOTENCY_TTL_SECONDS = 31_536_000L;

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
     * TTL in seconds for idempotency items, from {@code dynamodb.idempotency-ttl-seconds}.
     *
     * <p>Default {@code 2_592_000} seconds, 30 days, when the property is unset. That window is long enough for
     * clients to retry payment creation after outages or reconciliation delays while idempotency rows are
     * still retrievable, yet bounded so DynamoDB eventually drops stale bindings instead of keeping them
     * forever. Operators should align this value with legal and operational retention policy.
     * See {@link IdempotencyRecord} for TTL semantics on stored items.
     *
     * <p>Validated at startup to lie between {@link #MIN_IDEMPOTENCY_TTL_SECONDS} and
     * {@link #MAX_IDEMPOTENCY_TTL_SECONDS}.
     */
    @Value("${dynamodb.idempotency-ttl-seconds:2592000}")
    private long idempotencyTtlSeconds = 2_592_000L;

    /**
     * Fails fast at startup when required {@code dynamodb.*} properties are missing or blank, and
     * when {@code dynamodb.idempotency-ttl-seconds} falls outside the allowed range.
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
        if (idempotencyTtlSeconds < MIN_IDEMPOTENCY_TTL_SECONDS
                || idempotencyTtlSeconds > MAX_IDEMPOTENCY_TTL_SECONDS) {
            throw new IllegalStateException("dynamodb.idempotency-ttl-seconds must be between "
                    + MIN_IDEMPOTENCY_TTL_SECONDS + " and " + MAX_IDEMPOTENCY_TTL_SECONDS
                    + " inclusive, got: " + idempotencyTtlSeconds);
        }
    }

    /**
     * Returns the validated idempotency TTL in seconds.
     *
     * @return seconds between {@link #MIN_IDEMPOTENCY_TTL_SECONDS} and {@link #MAX_IDEMPOTENCY_TTL_SECONDS}
     */
    public long getIdempotencyTtlSeconds() {
        return idempotencyTtlSeconds;
    }

    /**
     * Builds the shared client override configuration: call timeouts plus the explicit retry strategy.
     *
     * <p><strong>Call timeouts.</strong> {@link #API_CALL_ATTEMPT_TIMEOUT} bounds each network attempt
     * and {@link #API_CALL_TIMEOUT} bounds the whole call across retries. A stalled attempt is dropped
     * at 1.5 s and retried, and the overall call gives up at 5 s with a timeout error instead of holding
     * the request thread until the operating system socket timeout. The two budgets interact, because
     * each attempt may take up to 1.5 s and the whole call is capped at 5 s, only the first few of the
     * {@code maxAttempts(9)} retries run before the call times out, raise {@link #API_CALL_TIMEOUT} to
     * use the full retry budget. Tune both for your workload.
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
     * @return the client override configuration applied to {@link #dynamoDbAsyncClient(ClientOverrideConfiguration)}
     * @see BackoffStrategy#exponentialDelay(Duration, Duration)
     */
    @Bean
    public ClientOverrideConfiguration dynamoDbClientOverrideConfiguration() {
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                .apiCallTimeout(API_CALL_TIMEOUT)
                .retryStrategy(retry -> retry
                        .maxAttempts(9)
                        .backoffStrategy(BackoffStrategy.exponentialDelay(
                                Duration.ofMillis(25), Duration.ofSeconds(20)))
                        .throttlingBackoffStrategy(BackoffStrategy.exponentialDelay(
                                Duration.ofSeconds(1), Duration.ofSeconds(20))))
                .build();
    }

    /**
     * Creates the low-level async DynamoDB client with the shared override configuration (call timeouts
     * and retry strategy from {@link #dynamoDbClientOverrideConfiguration()}).
     *
     * <p>Uses a custom endpoint and fake credentials when connecting to local DynamoDB.
     * Otherwise, uses the default AWS credential chain.
     *
     * <p><strong>HTTP client tuning.</strong> The async client uses the Netty
     * ({@link NettyNioAsyncHttpClient}) HTTP client. Its defaults ({@code maxConcurrency} 50,
     * {@code connectionAcquisitionTimeout} 10 s) can make the connection pool the bottleneck before
     * DynamoDB under burst load. {@link #HTTP_MAX_CONCURRENCY} sizes the pool to the request thread
     * pool and {@link #HTTP_CONNECTION_ACQUISITION_TIMEOUT} fails fast on a saturated pool instead of
     * queuing. Tune both for your workload.
     *
     * @param overrideConfiguration the shared client override configuration
     * @return the configured low-level async client
     */
    @Bean
    public DynamoDbAsyncClient dynamoDbAsyncClient(ClientOverrideConfiguration overrideConfiguration) {
        var builder = DynamoDbAsyncClient.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(endpoint))
                .httpClientBuilder(nettyHttpClientBuilder())
                .overrideConfiguration(overrideConfiguration);

        if (DynamoDbEndpointUtils.isLocalEndpoint(endpoint)) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")));
        }

        return builder.build();
    }

    /**
     * Builds the tuned Netty async HTTP client used by {@link #dynamoDbAsyncClient(ClientOverrideConfiguration)}.
     * Applies {@link #HTTP_MAX_CONCURRENCY} and {@link #HTTP_CONNECTION_ACQUISITION_TIMEOUT} instead of the
     * SDK defaults of 50 and 10 s. Extracted so the wired settings can be asserted in isolation.
     *
     * @return the configured Netty HTTP client builder
     */
    public NettyNioAsyncHttpClient.Builder nettyHttpClientBuilder() {
        return NettyNioAsyncHttpClient.builder()
                .maxConcurrency(HTTP_MAX_CONCURRENCY)
                .connectionAcquisitionTimeout(HTTP_CONNECTION_ACQUISITION_TIMEOUT);
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
