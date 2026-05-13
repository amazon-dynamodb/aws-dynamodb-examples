package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Registers the {@link DynamoDbStreamsAsyncClient} for polling table change streams.
 */
@Configuration
public class DynamoDbStreamsConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbStreamsConfig.class);

    @Value("${dynamodb.endpoint}")
    private String endpoint;

    @Value("${dynamodb.region}")
    private String region;

    /**
     * Async client for stream describe/getRecords; mirrors {@link DynamoDbConfig} endpoint/region and local credentials.
     *
     * @return client consumed by {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener}
     */
    @Bean
    public DynamoDbStreamsAsyncClient dynamoDbStreamsAsyncClient() {
        var builder = DynamoDbStreamsAsyncClient.builder()
                .region(Region.of(region));

        if (DynamoDbEndpointUtils.isLocalEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")));
        }

        log.info("Created DynamoDB Streams async client for endpoint '{}' in region '{}'", endpoint, region);
        return builder.build();
    }
}
