package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.DynamoDbEndpointUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Configures the DynamoDB Streams async client for consuming change data capture records.
 *
 * <p>The Streams client uses a separate service endpoint from the main DynamoDB client.
 * For local endpoints the same fake credentials approach is used.
 */
@Configuration
public class DynamoDbStreamsConfig {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbStreamsConfig.class);

    /** DynamoDB API endpoint, reused for local Streams endpoint override. */
    @Value("${dynamodb.endpoint:}")
    private String endpoint;

    /** AWS region for the Streams client. */
    @Value("${dynamodb.region:}")
    private String region;

    /**
     * Creates the DynamoDB Streams async client.
     *
     * <p>For local endpoints, uses endpoint override and fake credentials.
     * For AWS endpoints, relies on default credential chain and endpoint resolution.
     *
     * @return streams client bean for the leaderboard listener
     */
    @Bean(destroyMethod = "close")
    public DynamoDbStreamsAsyncClient dynamoDbStreamsAsyncClient() {
        var builder = DynamoDbStreamsAsyncClient.builder()
                .region(Region.of(region));

        if (DynamoDbEndpointUtils.isLocalEndpoint(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("fakeAccessKey", "fakeSecretKey")));
        }

        DynamoDbStreamsAsyncClient client = builder.build();
        logger.info("DynamoDB Streams async client initialized [region={}, endpoint={}]",
                region, endpoint);
        return client;
    }
}
