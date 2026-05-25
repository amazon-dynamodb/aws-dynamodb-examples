package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for smoke tests: lightweight API-level sanity checks using {@link MockMvc}.
 *
 * <p>{@link MockMvc} is provided by {@code @AutoConfigureMockMvc} on {@link AbstractIntegrationTest}.
 */
public abstract class AbstractSmokeTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;
}
