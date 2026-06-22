package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies the security-relevant settings in {@code application.yml} stay pinned.
 *
 * <p>It loads the packaged YAML and asserts the actuator exposure lockdown and the production
 * profile that disables springdoc, so a future edit cannot silently widen the surface.
 */
@Tag("unit")
class ApplicationYamlConfigTest {

    @Test
    void applicationYaml_whenLoaded_shouldPinActuatorExposureToHealthOnly() throws IOException {
        PropertySource<?> defaultDocument = loadDocuments().get(0);

        assertThat(defaultDocument.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(defaultDocument.getProperty("management.endpoint.health.show-details"))
                .isEqualTo("never");
    }

    @Test
    void applicationYaml_whenLoaded_shouldConfigureGracefulShutdown() throws IOException {
        PropertySource<?> defaultDocument = loadDocuments().get(0);

        assertThat(defaultDocument.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(defaultDocument.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("20s");
    }

    @Test
    void applicationYaml_whenProdProfileDocumentLoaded_shouldDisableSpringdoc() throws IOException {
        List<PropertySource<?>> documents = loadDocuments();
        assertThat(documents).hasSize(2);

        PropertySource<?> prodDocument = documents.get(1);
        assertThat(prodDocument.getProperty("spring.config.activate.on-profile")).isEqualTo("prod");
        assertThat(prodDocument.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(prodDocument.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
    }

    /**
     * Loads the packaged {@code application.yml} as one property source per YAML document.
     *
     * <p>The default document is first and the production profile document is second.
     *
     * @return property sources in document order
     * @throws IOException if the resource cannot be read
     */
    private static List<PropertySource<?>> loadDocuments() throws IOException {
        return new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
    }
}


