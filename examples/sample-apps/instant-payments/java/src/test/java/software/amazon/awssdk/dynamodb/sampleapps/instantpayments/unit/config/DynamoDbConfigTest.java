package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbConfig;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link DynamoDbConfig} property validation, client bean registration, the
 * call timeouts ({@code apiCallAttemptTimeout}, {@code apiCallTimeout}) on the override configuration,
 * and the tuned Netty HTTP client settings ({@code maxConcurrency}, {@code connectionAcquisitionTimeout}).
 */
@Tag("unit")
public class DynamoDbConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbConfig.class);

    @Test
    void dynamoDbClients_whenPropertiesValid_shouldCreateClients() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                    assertThat(context).hasSingleBean(DynamoDbEnhancedAsyncClient.class);
                });
    }

    @Test
    void dynamoDbClientOverrideConfiguration_whenBuilt_shouldSetCallTimeouts() {
        ClientOverrideConfiguration override = new DynamoDbConfig().dynamoDbClientOverrideConfiguration();

        assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofMillis(1500));
        assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void nettyHttpClientBuilder_whenBuilt_shouldApplyTunedConcurrencyAndAcquisitionTimeout() throws Exception {
        // Assert the tuned values are genuinely applied to the built Netty client, not just held as
        // constants. Reads the resolved NettyConfiguration off the built client and compares against the
        // SDK defaults (maxConnections 50, connectionAcquireTimeout 10 s) the tuning is meant to replace.
        try (SdkAsyncHttpClient httpClient =
                     new DynamoDbConfig().nettyHttpClientBuilder().build()) {

            assertThat(httpClient).isInstanceOf(NettyNioAsyncHttpClient.class);
            Object nettyConfiguration = extractResolvedNettyConfiguration((NettyNioAsyncHttpClient) httpClient);

            int maxConnections = (int) invokeAccessor(nettyConfiguration, "maxConnections");
            int connectionAcquireTimeoutMillis = (int) invokeAccessor(nettyConfiguration, "connectionAcquireTimeoutMillis");

            assertThat(maxConnections)
                    .as("tuned maxConcurrency must be applied to the Netty client, not the SDK default of 50")
                    .isEqualTo(200)
                    .isNotEqualTo(50);
            assertThat(connectionAcquireTimeoutMillis)
                    .as("tuned connectionAcquisitionTimeout must be applied, not the SDK default of 10 s")
                    .isEqualTo((int) Duration.ofSeconds(2).toMillis())
                    .isNotEqualTo((int) Duration.ofSeconds(10).toMillis());
        }
    }

    @Test
    void nettyHttpClientBuilder_whenBuilt_shouldKeepAcquisitionTimeoutWithinAttemptTimeoutBudget() throws Exception {
        // The acquisition wait must stay coherent with apiCallAttemptTimeout (1.5 s) so the attempt times
        // out first on a saturated pool rather than the long 10 s default queuing.
        Duration acquisitionTimeout = readDurationConstant("HTTP_CONNECTION_ACQUISITION_TIMEOUT");
        Duration attemptTimeout = readDurationConstant("API_CALL_ATTEMPT_TIMEOUT");

        assertThat(acquisitionTimeout)
                .isLessThanOrEqualTo(Duration.ofSeconds(3))
                .isGreaterThan(attemptTimeout);
    }

    @Test
    void dynamoDbAsyncClient_whenPropertiesValid_shouldBuildWithTunedHttpClient() {
        // Proves the tuned NettyNioAsyncHttpClient settings are accepted by the builder and the bean
        // is created without error through the Spring wiring path.
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasSingleBean(DynamoDbAsyncClient.class));
    }

    @Test
    void dynamoDbClientOverrideConfiguration_whenRegisteredAsBean_shouldCarryCallTimeouts() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    ClientOverrideConfiguration override =
                            context.getBean(ClientOverrideConfiguration.class);
                    assertThat(override.apiCallAttemptTimeout()).contains(Duration.ofMillis(1500));
                    assertThat(override.apiCallTimeout()).contains(Duration.ofSeconds(5));
                });
    }

    @Test
    void dynamoDbAsyncClient_whenEndpointNeverResponds_shouldFailWithApiCallTimeoutWithinBudget() throws Exception {
        ClientOverrideConfiguration override = new DynamoDbConfig().dynamoDbClientOverrideConfiguration();
        List<Socket> heldConnections = new CopyOnWriteArrayList<>();

        // A server that accepts the TCP connection but never sends an HTTP response, so the call hangs
        // waiting for a reply. Without the call timeouts this would block until the OS socket timeout.
        try (ServerSocket blackHole = new ServerSocket(0)) {
            int port = blackHole.getLocalPort();
            Thread accepter = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        heldConnections.add(blackHole.accept());
                    }
                } catch (IOException ignored) {
                    // socket closed when the test tears down, expected
                }
            });
            accepter.setDaemon(true);
            accepter.start();

            try (DynamoDbAsyncClient client = DynamoDbAsyncClient.builder()
                    .region(Region.EU_WEST_1)
                    .endpointOverride(URI.create("http://127.0.0.1:" + port))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")))
                    .overrideConfiguration(override)
                    .build()) {

                long startNanos = System.nanoTime();
                CompletableFuture<?> future = client.getItem(r -> r
                        .tableName("does-not-matter")
                        .key(Map.of("PK", AttributeValue.fromS("x"))));

                Throwable thrown = catchThrowable(future::join);
                long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                assertThat(thrown).isNotNull();
                assertThat(isTimeoutFailure(thrown))
                        .as("expected an API call timeout, but was: %s", thrown)
                        .isTrue();
                // apiCallTimeout is 5 s. Allow slack but prove it failed fast rather than hanging.
                assertThat(elapsedMillis).isLessThan(Duration.ofSeconds(9).toMillis());
            } finally {
                accepter.interrupt();
                for (Socket socket : heldConnections) {
                    socket.close();
                }
            }
        }
    }

    @Test
    void dynamoDbClients_whenEndpointBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void dynamoDbClients_whenRegionBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=  ",
                        "dynamodb.client-type=high-level")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void dynamoDbClients_whenClientTypeBlank_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validate_whenIdempotencyTtlWithinRange_shouldStartSuccessfully() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.idempotency-ttl-seconds=86400")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DynamoDbConfig.class).getIdempotencyTtlSeconds())
                            .isEqualTo(86_400L);
                });
    }

    @Test
    void validate_whenIdempotencyTtlBelowMinimum_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.idempotency-ttl-seconds=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validate_whenIdempotencyTtlAboveMaximum_shouldFailContext() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level",
                        "dynamodb.idempotency-ttl-seconds=999999999")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void validate_whenIdempotencyTtlDefault_shouldUseDefaultValue() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.endpoint=http://localhost:8000",
                        "dynamodb.region=eu-west-1",
                        "dynamodb.client-type=high-level")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DynamoDbConfig.class).getIdempotencyTtlSeconds())
                            .isEqualTo(2_592_000L);
                });
    }

    /** Walks the cause chain to detect an SDK call or attempt timeout. */
    private static boolean isTimeoutFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiCallTimeoutException || cause instanceof ApiCallAttemptTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Reflectively reads a private static {@link Duration} constant by field name. */
    private static Duration readDurationConstant(String fieldName) throws Exception {
        Field field = DynamoDbConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Duration) field.get(null);
    }

    /**
     * Reads the resolved {@code NettyConfiguration} off a built {@link NettyNioAsyncHttpClient}. This is
     * SDK-internal state with no public accessor, so it is reached reflectively. If a future SDK upgrade
     * renames the field this test must be revisited, which is the intended early-warning.
     */
    private static Object extractResolvedNettyConfiguration(NettyNioAsyncHttpClient client) throws Exception {
        Field field = NettyNioAsyncHttpClient.class.getDeclaredField("configuration");
        field.setAccessible(true);
        return field.get(client);
    }

    /** Invokes a no-argument accessor on the resolved Netty configuration (for example {@code maxConnections}). */
    private static Object invokeAccessor(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
