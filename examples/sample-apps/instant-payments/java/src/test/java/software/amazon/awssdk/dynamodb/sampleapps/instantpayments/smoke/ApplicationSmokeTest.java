package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.smoke;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Smoke test verifying that the application starts up correctly and core endpoints are accessible.
 */
@Tag("smoke")
public class ApplicationSmokeTest extends AbstractIntegrationTest {

    /** Verifies the actuator health endpoint returns 200. */
    @Test
    void healthEndpointShouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** Verifies the OpenAPI spec is accessible and contains the expected API title. */
    @Test
    void openApiSpecShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Instant Payments DynamoDB Sample API"));
    }
}
