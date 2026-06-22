package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.RetryHelper;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Configures DynamoDB client beans based on application properties.
 *
 * <p>Required properties (no defaults, fail fast if missing or blank):
 * <ul>
 *   <li>{@code dynamodb.endpoint} full endpoint URL (for example {@code http://localhost:8000} or
 *       {@code https://dynamodb.eu-west-1.amazonaws.com})</li>
 *   <li>{@code dynamodb.region} AWS region (for example {@code eu-west-1})</li>
 *   <li>{@code dynamodb.client-type} {@code high-level} (enhanced client) or {@code low-level}</li>
 * </ul>
 *
 * <p>When the endpoint host is local ({@code localhost}, {@code dynamodb}, and similar), fake credentials
 * are used. Otherwise, the default AWS credential chain is used.
 *
 * <p>The low-level {@link DynamoDbAsyncClient} is always created for table management and serves
 * as the foundation for the enhanced client.
 */
@Configuration
public class DynamoDbConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbConfig.class);

    /** Minimum accepted value for {@code dynamodb.game-events-ttl-seconds}. */
    private static final long MIN_GAME_EVENTS_TTL_SECONDS = 60L;

    /** Maximum accepted value for {@code dynamodb.game-events-ttl-seconds}, one year in seconds. */
    private static final long MAX_GAME_EVENTS_TTL_SECONDS = 31_536_000L;

    /**
     * Per-attempt timeout. A single network attempt that has not received a response within this
     * budget is abandoned and handed back to the retry strategy, so one slow connection never pins a
     * request thread waiting on a reply.
     */
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofMillis(1500);

    /**
     * End-to-end call timeout across all retries. The whole operation gives up once this elapses and
     * returns a timeout to the caller, so a request fails fast with a clear error instead of hanging.
     * This overall budget also caps how many of the configured retry attempts can actually run.
     */
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Maximum number of concurrent connections the async Netty HTTP client keeps open to DynamoDB.
     * The SDK default is 50. Sizing this pool generously avoids the Netty client becoming the limiter
     * before DynamoDB under burst load.
     */
    static final int HTTP_MAX_CONCURRENCY = 200;

    /**
     * How long a caller waits for a connection to free up from the pool before failing. The SDK
     * default is 10 seconds, which under burst load adds a long latency tail and outlives the call
     * budget. Kept coherent with {@link #API_CALL_ATTEMPT_TIMEOUT} so a saturated pool fails fast
     * instead of queuing.
     */
    static final Duration HTTP_CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(2);

    /** DynamoDB API base URL from {@code dynamodb.endpoint}. */
    @Value("${dynamodb.endpoint:}")
    private String endpoint;

    /** AWS region string from {@code dynamodb.region}. */
    @Value("${dynamodb.region:}")
    private String region;

    /** Selects repository wiring, {@code high-level} or {@code low-level}, from {@code dynamodb.client-type}. */
    @Value("${dynamodb.client-type:}")
    private String clientType;

    /** Event time-to-live in seconds from {@code dynamodb.game-events-ttl-seconds}. */
    @Value("${dynamodb.game-events-ttl-seconds:7776000}")
    private long gameEventsTtlSeconds;

    /**
     * Fails fast at startup when required {@code dynamodb.*} properties are missing or blank.
     *
     * <p>It also enforces the accepted range for {@code dynamodb.game-events-ttl-seconds} so an
     * out-of-range value is rejected at startup instead of being applied to every event.
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
        if (gameEventsTtlSeconds < MIN_GAME_EVENTS_TTL_SECONDS
                || gameEventsTtlSeconds > MAX_GAME_EVENTS_TTL_SECONDS) {
            throw new IllegalStateException(
                    "dynamodb.game-events-ttl-seconds must be between " + MIN_GAME_EVENTS_TTL_SECONDS
                            + " and " + MAX_GAME_EVENTS_TTL_SECONDS);
        }
    }

    /**
     * Builds the shared client override configuration: call timeouts plus the explicit retry strategy.
     *
     * <p><strong>Call timeouts.</strong> {@link #API_CALL_ATTEMPT_TIMEOUT} bounds each network attempt
     * and {@link #API_CALL_TIMEOUT} bounds the whole call across retries. A stalled attempt is dropped
     * and retried, and the overall call gives up with a timeout error instead of holding the request
     * thread until the operating system socket timeout. The two budgets interact, so only the first
     * few of the {@code maxAttempts(9)} retries run before the call times out. Tune both for your
     * workload.
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
     * retry for those keys is implemented via {@link RetryHelper#executeBatchGetUntilComplete} and
     * {@link RetryHelper#executeEnhancedBatchGetUntilComplete} on
     * {@link PlayerStateRepository#batchGetPlayers(List)}.
     *
     * <p>The streams client reuses this same override so the poller stays consistent with the main
     * client under throttling.
     *
     * @return the shared override applied to the main and streams clients
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
     * Creates the low-level async DynamoDB client with the shared override configuration and a tuned
     * Netty HTTP client.
     *
     * <p>Uses a custom endpoint and fake credentials when connecting to local DynamoDB. Otherwise
     * uses the default AWS credential chain.
     *
     * <p><strong>HTTP client tuning.</strong> The Netty defaults ({@code maxConcurrency} 50,
     * {@code connectionAcquisitionTimeout} 10 s) can make the connection pool the bottleneck before
     * DynamoDB under burst load. {@link #HTTP_MAX_CONCURRENCY} sizes the pool and
     * {@link #HTTP_CONNECTION_ACQUISITION_TIMEOUT} fails fast on a saturated pool instead of queuing.
     *
     * @param overrideConfiguration the shared override from {@link #dynamoDbClientOverrideConfiguration()}
     * @return shared async client for the module
     */
    @Bean(destroyMethod = "close")
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

        DynamoDbAsyncClient client = builder.build();
        logger.debug("DynamoDB async client initialized [region={}, endpoint={}, clientType={}]",
                region, endpoint, clientType);
        return client;
    }

    /**
     * Builds the tuned Netty async HTTP client used by {@link #dynamoDbAsyncClient(ClientOverrideConfiguration)}.
     *
     * <p>Applies {@link #HTTP_MAX_CONCURRENCY} and {@link #HTTP_CONNECTION_ACQUISITION_TIMEOUT} instead
     * of the SDK defaults of 50 and 10 seconds. Extracted so the wired settings can be asserted in
     * isolation.
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
     * <p>Registers {@link VersionedRecordExtension} so mapped beans annotated with
     * {@code @DynamoDbVersionAttribute} receive optimistic locking on updates via {@link VersionedRecordExtension}.
     *
     * <p>Only created when {@code dynamodb.client-type=high-level}.
     *
     * @param dynamoDbAsyncClient low-level client backing the enhanced client
     * @return enhanced client bean
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
