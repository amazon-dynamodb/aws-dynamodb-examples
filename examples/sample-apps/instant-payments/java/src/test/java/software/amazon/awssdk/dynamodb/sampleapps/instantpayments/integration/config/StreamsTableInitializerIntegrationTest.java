package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;

/**
 * Verifies that startup table creation enables DynamoDB Streams ({@code NEW_IMAGE}).
 */
@Tag("integration")
public class StreamsTableInitializerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void initializeTable_whenApplicationStarts_shouldEnableStream() {
        var response = dynamoDbAsyncClient.describeTable(
                DescribeTableRequest.builder().tableName(tableName).build()).join();

        assertThat(response.table().streamSpecification().streamEnabled()).isTrue();
        assertThat(response.table().streamSpecification().streamViewType()).isEqualTo(StreamViewType.NEW_IMAGE);
        assertThat(response.table().latestStreamArn()).isNotBlank();
    }
}
