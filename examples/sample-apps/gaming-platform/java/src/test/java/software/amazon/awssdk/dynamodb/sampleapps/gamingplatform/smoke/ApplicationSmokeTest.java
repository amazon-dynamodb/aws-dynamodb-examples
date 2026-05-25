package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke;

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
 * Smoke tests for core application wiring and infrastructure endpoints.
 *
 * <p>Extends {@link AbstractSmokeTest} with the full Spring context and DynamoDB Local from
 * Testcontainers. Uses MockMvc to verify {@code /actuator/health} and the OpenAPI spec at
 * {@code /api-docs}. Also asserts {@link DynamoDbConfig} applies an explicit SDK retry strategy
 * on the wired {@link DynamoDbAsyncClient} and optional {@link DynamoDbEnhancedAsyncClient}.
 */
@Tag("smoke")
public class ApplicationSmokeTest extends AbstractSmokeTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Autowired(required = false)
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Test
    void healthEndpointShouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiSpecShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Gaming Platform DynamoDB Sample API"))
                .andExpect(jsonPath("$.servers.length()").value(1))
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8080"));
    }

    @Test
    void dynamoDbClientsShouldUseExplicitSdkRetryStrategy() {
        DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbAsyncClient);
        if (dynamoDbEnhancedAsyncClient != null) {
            DynamoDbClientRetryAssertions.assertExplicitSdkRetryStrategy(dynamoDbEnhancedAsyncClient);
        }
    }
}
