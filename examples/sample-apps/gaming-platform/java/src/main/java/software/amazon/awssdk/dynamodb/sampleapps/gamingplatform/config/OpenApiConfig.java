package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the OpenAPI 3.0 specification served by springdoc.
 *
 * <p>Swagger UI is available at {@code /swagger-ui.html}.
 * The raw OpenAPI JSON spec is at {@code /api-docs}.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Builds the OpenAPI 3.0 specification for the Gaming Platform API.
     *
     * @return the OpenAPI model served at {@code /api-docs} and rendered in Swagger UI
     */
    @Bean
    public OpenAPI gamingPlatformOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Gaming Platform DynamoDB Sample API")
                        .description("Sample application demonstrating DynamoDB patterns for a cross-platform "
                                + "gaming backend using AWS SDK for Java v2. Covers idempotent player "
                                + "registration, player snapshot reads and writes, atomic purchases, "
                                + "lobby batch reads, append-only game events with TTL, leaderboard queries, "
                                + "and asynchronous leaderboard maintenance from DynamoDB Streams. Switch "
                                + "repository implementations with dynamodb.client-type.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("AWS SDK for Java Team")))
                .servers(List.of(new Server().url("http://localhost:8080")));
    }
}
