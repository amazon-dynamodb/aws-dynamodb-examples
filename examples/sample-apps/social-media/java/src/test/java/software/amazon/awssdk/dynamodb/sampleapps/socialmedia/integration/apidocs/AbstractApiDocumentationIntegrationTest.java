package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.integration.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * API documentation integration tests booting the full web application against DynamoDB Local.
 *
 * <p>The same scenarios run for every {@code dynamodb.client-type} via the concrete subclasses so
 * both access styles are verified end-to-end. A single DynamoDB Local container is shared across the
 * client-type subclasses (singleton pattern) and the streams poller is disabled so it does not race
 * these HTTP-only tests.
 */
abstract class AbstractApiDocumentationIntegrationTest {

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
     * disables the streams poller for these HTTP-only tests.
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
    void apiDocs_whenEnabled_returnsOpenApiJsonWithInfoBlockAndServer() throws JsonProcessingException {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode openApi = new ObjectMapper().readTree(response.getBody());
        assertThat(openApi.path("openapi").asText()).isNotBlank();
        JsonNode info = openApi.path("info");
        assertThat(info.path("title").asText()).isNotBlank();
        assertThat(info.path("description").asText()).isNotBlank();
        assertThat(info.path("version").asText()).isNotBlank();
        assertThat(info.path("contact").path("name").asText()).isNotBlank();
        assertThat(openApi.path("servers").isArray()).isTrue();
        assertThat(openApi.path("servers").size()).isGreaterThan(0);
        assertThat(openApi.path("servers").get(0).path("url").asText()).isNotBlank();
    }

    @Test
    void apiDocs_whenEnabled_advertisesLimitMinimumOneAndMaximumOneHundred() throws JsonProcessingException {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode openApi = new ObjectMapper().readTree(response.getBody());
        assertLimitBounds(openApi, "/api/v1/timeline/{userId}");
        assertLimitBounds(openApi, "/api/v1/inbox/{userId}");
    }

    @Test
    void apiDocs_whenEnabled_advertisesDocumentedResponseCodes() throws JsonProcessingException {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode openApi = new ObjectMapper().readTree(response.getBody());
        assertResponseCodes(openApi, "/api/v1/users", "post", "200", "201", "400", "500", "503");
        assertResponseCodes(openApi, "/api/v1/users/{targetUserId}/follows", "post",
                "200", "400", "404", "409", "500", "503");
        assertResponseCodes(openApi, "/api/v1/media", "post", "200", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/posts", "post", "201", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/posts/{postId}/likes", "post",
                "200", "400", "404", "409", "500", "503");
        assertResponseCodes(openApi, "/api/v1/posts/{postId}/context", "get",
                "200", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/timeline/{userId}", "get", "200", "400", "500", "503");
        assertResponseCodes(openApi, "/api/v1/conversations", "post", "201", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/conversations/{conversationId}/messages", "post",
                "201", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/conversations/{conversationId}", "get",
                "200", "400", "404", "500", "503");
        assertResponseCodes(openApi, "/api/v1/inbox/{userId}", "get", "200", "400", "404", "500", "503");
    }

    @Test
    void swaggerUi_whenEnabled_redirectsToInteractiveDocumentation() throws IOException {
        URI location = redirectLocation("/swagger-ui.html");
        assertThat(location).isNotNull();
        assertThat(location.getPath()).isEqualTo("/swagger-ui/index.html");

        URI follow = location.isAbsolute() ? location : URI.create(url(location.getPath()));
        ResponseEntity<String> ui = restTemplate.getForEntity(follow, String.class);
        assertThat(ui.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ui.getBody()).isNotBlank();
    }

    /**
     * Asserts that the GET {@code limit} query parameter on {@code path} advertises minimum 1,
     * maximum 100, and default 50.
     *
     * @param openApi generated OpenAPI document
     * @param path    OpenAPI path key
     */
    private void assertLimitBounds(JsonNode openApi, String path) {
        JsonNode limitSchema = queryParameterSchema(openApi, path, "limit");
        assertThat(limitSchema.path("minimum").asInt()).isEqualTo(1);
        assertThat(limitSchema.path("maximum").asInt()).isEqualTo(100);
        assertThat(limitSchema.path("default").asInt()).isEqualTo(50);
    }

    /**
     * Asserts that the named operation advertises every expected HTTP response code.
     *
     * @param openApi generated OpenAPI document
     * @param path    OpenAPI path key
     * @param method  HTTP method in lowercase
     * @param codes   response codes that must be present
     */
    private void assertResponseCodes(JsonNode openApi, String path, String method, String... codes) {
        JsonNode responses = openApi.path("paths").path(path).path(method).path("responses");
        assertThat(responses.isObject())
                .as("Missing OpenAPI operation %s %s", method, path)
                .isTrue();
        for (String code : codes) {
            assertThat(responses.has(code))
                    .as("Missing response %s on %s %s", code, method, path)
                    .isTrue();
        }
    }

    /**
     * Returns the schema node for a named query parameter on a GET operation.
     *
     * @param openApi generated OpenAPI document
     * @param path    OpenAPI path key
     * @param name    query parameter name
     * @return the parameter schema node
     */
    private JsonNode queryParameterSchema(JsonNode openApi, String path, String name) {
        JsonNode parameters = openApi.path("paths").path(path).path("get").path("parameters");
        assertThat(parameters.isArray()).isTrue();
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.path("name").asText())) {
                JsonNode schema = parameter.path("schema");
                assertThat(schema.isMissingNode()).isFalse();
                return schema;
            }
        }
        throw new AssertionError("Missing query parameter " + name + " on " + path);
    }

    /**
     * Issues a GET that does not follow redirects so the {@code Location} header can be asserted.
     *
     * @param path request path beginning with {@code /}
     * @return redirect {@code Location}, or {@code null} when the header is absent
     * @throws IOException if the connection fails
     */
    private URI redirectLocation(String path) throws IOException {
        URL target = URI.create(url(path)).toURL();
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        assertThat(HttpStatus.valueOf(status).is3xxRedirection())
                .as("GET %s should redirect, status was %s", path, status)
                .isTrue();
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        return location == null ? null : URI.create(location);
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
