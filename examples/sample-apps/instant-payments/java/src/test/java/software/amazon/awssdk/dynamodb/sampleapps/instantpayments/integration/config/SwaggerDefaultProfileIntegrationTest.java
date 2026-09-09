package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Verifies that the interactive API docs are enabled when the {@code prod} profile is not active.
 *
 * <p>This is the counterpart to {@link SwaggerProdProfileIntegrationTest}: under the default
 * {@code test} profile (no {@code prod} override) {@code springdoc} is enabled, so the OpenAPI JSON
 * is served and the Swagger UI redirects to its index page rather than returning 404.
 */
@Tag("integration")
public class SwaggerDefaultProfileIntegrationTest extends AbstractIntegrationTest {

    @Test
    void apiDocs_whenProdProfileInactive_shouldBeServed() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Instant Payments DynamoDB Sample API"));
    }

    @Test
    void apiDocs_whenAsyncControllersPresent_shouldExposeDtoSchemasNotCompletableFuture() throws Exception {
        MvcResult result = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("GetAccountResponse");
        assertThat(body).doesNotContain("CompletableFuture");
    }

    @Test
    void swaggerUi_whenProdProfileInactive_shouldRedirectToServableIndex() throws Exception {
        // springdoc serves /swagger-ui.html as a redirect to /swagger-ui/index.html when enabled.
        String location = mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"))
                .andReturn().getResponse().getRedirectedUrl();

        // Follow the redirect and prove the Swagger UI index is actually served as HTML, not just a dangling redirect.
        mockMvc.perform(get(Objects.requireNonNull(location)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
