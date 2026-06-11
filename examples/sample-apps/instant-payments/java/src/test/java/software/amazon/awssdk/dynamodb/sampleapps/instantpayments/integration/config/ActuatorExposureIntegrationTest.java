package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;

/**
 * Verifies that actuator web exposure is locked down to {@code health} only (M5).
 *
 * <p>{@code management.endpoints.web.exposure.include} is pinned to {@code health} and
 * {@code management.endpoint.health.show-details} is {@code never}, so {@code /actuator/health}
 * answers with status only while sensitive operational endpoints ({@code env}, {@code beans},
 * {@code configprops}, {@code heapdump}) are not web-exposed and return 404.
 */
@Tag("integration")
public class ActuatorExposureIntegrationTest extends AbstractIntegrationTest {

    @Test
    void health_whenRequested_shouldBeExposedWithStatusOnly() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details=never, so component and disk details are not leaked.
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void sensitiveEndpoints_whenRequested_shouldNotBeExposed() throws Exception {
        for (String endpoint : new String[] {"env", "beans", "configprops", "heapdump", "mappings", "loggers"}) {
            mockMvc.perform(get("/actuator/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }
}
