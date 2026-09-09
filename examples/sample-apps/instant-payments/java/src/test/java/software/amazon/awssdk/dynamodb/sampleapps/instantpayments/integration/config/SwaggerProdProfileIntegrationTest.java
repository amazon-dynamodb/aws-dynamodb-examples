package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Verifies that the {@code prod} profile disables the interactive API docs.
 *
 * <p>{@link ActiveProfiles} here is merged with the base {@code test} profile (inherited profiles),
 * so the application still boots against DynamoDB Local while the {@code prod} overrides set
 * {@code springdoc.api-docs.enabled} and {@code springdoc.swagger-ui.enabled} to false. Both the
 * OpenAPI JSON and the Swagger UI then return 404. The enabled-by-default case (no {@code prod}
 * profile) is covered by {@code ApplicationSmokeTest.openApiSpec_whenRequested_shouldBeAccessible}.
 */
@Tag("integration")
@ActiveProfiles("prod")
public class SwaggerProdProfileIntegrationTest extends AbstractIntegrationTest {

    @Test
    void apiDocs_whenProdProfile_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void swaggerUi_whenProdProfile_shouldReturn404() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isNotFound());
    }
}
