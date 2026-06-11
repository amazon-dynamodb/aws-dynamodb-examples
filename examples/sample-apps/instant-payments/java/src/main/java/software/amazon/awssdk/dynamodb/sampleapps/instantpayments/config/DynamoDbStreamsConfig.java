package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Registers the {@link DynamoDbStreamsAsyncClient} for polling table change streams.
 *
 * <p>The streams client shares the same retry strategy and call timeouts as the main client by
 * applying the {@link ClientOverrideConfiguration} bean built in {@link DynamoDbConfig#dynamoDbClientOverrideConfiguration()}.
 * Reusing that shared customizer keeps the poller and the main client consistent under throttling
 * instead of letting the streams client fall back to SDK default retries.
 */
@Configuration
public class DynamoDbStreamsConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbStreamsConfig.class);

    /** DynamoDB API endpoint URL from {@code dynamodb.endpoint}. */
    @Value("${dynamodb.endpoint}")
    private String endpoint;

    /** AWS region string from {@code dynamodb.region}. */
    @Value("${dynamodb.region}")
    private String region;

    /**
     * Async client for stream describe and getRecords. Mirrors {@link DynamoDbConfig} endpoint, region, and local credentials.
     *
     * <p>Applies the shared {@code overrideConfiguration} so the streams client carries the same retry
     * strategy and call timeouts as the main {@link DynamoDbAsyncClient}.
     *
     * @param overrideConfiguration the shared client override configuration from {@link DynamoDbConfig#dynamoDbClientOverrideConfiguration()}
     * @return client consumed by {@link DynamoDbStreamsPaymentEventListener}
     */
    @Bean
    public DynamoDbStreamsAsyncClient dynamoDbStreamsAsyncClient(ClientOverrideConfiguration overrideConfiguration) {
        var builder = DynamoDbStreamsAsyncClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(overrideConfiguration);

        if (DynamoDbEndpointUtils.isLocalEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")));
        }

        logger.debug("Created DynamoDB Streams async client: endpoint={}, region={}", endpoint, region);
        return builder.build();
    }
}
