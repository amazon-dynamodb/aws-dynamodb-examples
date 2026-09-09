package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.integration.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Production-profile API documentation test: the docs surface is removed under the
 * production-oriented profile.
 *
 * <p>Boots the full web application with the {@code prod} profile active, which disables springdoc,
 * so {@code GET /api-docs} and {@code GET /swagger-ui.html} must each return {@code 404 Not Found}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ApiDocumentationProdDocsDisabledIntegrationTest {

    private static final int DYNAMODB_PORT = 8000;

    static final GenericContainer<?> DYNAMODB =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
                    .withExposedPorts(DYNAMODB_PORT);

    static {
        DYNAMODB.start();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Points the application at the shared DynamoDB Local container, enables resource creation, and
     * disables the streams poller for this HTTP-only check.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint",
                () -> "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(DYNAMODB_PORT));
        registry.add("dynamodb.create-resources", () -> "true");
        registry.add("dynamodb.streams.enabled", () -> "false");
    }

    @Test
    void apiDocs_whenProductionProfile_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void swaggerUi_whenProductionProfile_returnsNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/swagger-ui.html"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Builds an absolute URL for a path on the random local port.
     *
     * @param path request path beginning with {@code /}
     * @return absolute URL
     */
    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
