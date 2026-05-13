package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
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
     * Builds the OpenAPI 3.0 specification for the Instant Payments API.
     *
     * @return the OpenAPI model served at {@code /api-docs} and rendered in Swagger UI
     */
    @Bean
    public OpenAPI instantPaymentsOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Instant Payments DynamoDB Sample API")
                        .description("Sample application demonstrating DynamoDB patterns for instant payments "
                                + "using AWS SDK for Java v2. Covers idempotent payment creation, "
                                + "atomic financial operations, safe state transitions, and efficient query patterns.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("AWS SDK for Java Team")));
    }
}
