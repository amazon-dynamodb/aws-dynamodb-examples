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

    @Test
    void faviconRequest_whenRequested_shouldReturn204() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent());
    }

    @Test
    void openApiSpec_whenRequested_shouldBeAccessible() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Instant Payments DynamoDB Sample API"));
    }
}
