package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke;

import static software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.support.AsyncMockMvcTestSupport.performAsync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils.DynamoDbClientRetryAssertions;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Smoke tests for core application wiring and API documentation.
 *
 * <p>Extends {@link AbstractSmokeTest} with the full Spring context and DynamoDB Local from
 * Testcontainers. Uses MockMvc to verify the OpenAPI spec at {@code /api-docs}. Also asserts
 * {@link DynamoDbConfig} applies an explicit SDK retry strategy on the wired
 * {@link DynamoDbAsyncClient} and optional {@link DynamoDbEnhancedAsyncClient}.
 */
@Tag("smoke")
public class ApplicationSmokeTest extends AbstractSmokeTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Autowired(required = false)
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Test
    void favicon_whenRequested_shouldReturn204() throws Exception {
        performAsync(mockMvc, get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    @Test
    void openApiSpec_whenRequested_shouldBeAccessible() throws Exception {
        performAsync(mockMvc, get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Gaming Platform DynamoDB Sample API"))
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8080"));
    }

    @Test
    void dynamoDbClients_whenWired_shouldUseExplicitSdkRetryStrategy() {
        DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbAsyncClient);
        if (dynamoDbEnhancedAsyncClient != null) {
            DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbEnhancedAsyncClient);
        }
    }
}
