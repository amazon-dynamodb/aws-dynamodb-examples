package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.WebMvcAsyncConfig;

/**
 * Unit coverage for MVC async pool and request-timeout defaults.
 *
 * <p>Confirms Spring property binding uses the named constants when the four keys are absent, and
 * that explicit values override those defaults. No Docker is required.
 */
@Tag("unit")
class WebMvcAsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebMvcAsyncConfig.class);

    @Test
    void context_whenPropertiesAbsent_defaultsToNamedConstants() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            WebMvcAsyncConfig config = context.getBean(WebMvcAsyncConfig.class);
            assertThat((Long) ReflectionTestUtils.getField(config, "requestTimeoutMillis"))
                    .isEqualTo(WebMvcAsyncConfig.DEFAULT_REQUEST_TIMEOUT_MILLIS);
            assertThat((Integer) ReflectionTestUtils.getField(config, "corePoolSize"))
                    .isEqualTo(WebMvcAsyncConfig.DEFAULT_CORE_POOL_SIZE);
            assertThat((Integer) ReflectionTestUtils.getField(config, "maxPoolSize"))
                    .isEqualTo(WebMvcAsyncConfig.DEFAULT_MAX_POOL_SIZE);
            assertThat((Integer) ReflectionTestUtils.getField(config, "queueCapacity"))
                    .isEqualTo(WebMvcAsyncConfig.DEFAULT_QUEUE_CAPACITY);
        });
    }

    @Test
    void context_whenPropertiesOverrideDefaults_bindsConfiguredValues() {
        contextRunner.withPropertyValues(
                        "spring.mvc.async.request-timeout=20000",
                        "social-media.mvc.async.core-pool-size=4",
                        "social-media.mvc.async.max-pool-size=8",
                        "social-media.mvc.async.queue-capacity=16")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WebMvcAsyncConfig config = context.getBean(WebMvcAsyncConfig.class);
                    assertThat((Long) ReflectionTestUtils.getField(config, "requestTimeoutMillis")).isEqualTo(20000L);
                    assertThat((Integer) ReflectionTestUtils.getField(config, "corePoolSize")).isEqualTo(4);
                    assertThat((Integer) ReflectionTestUtils.getField(config, "maxPoolSize")).isEqualTo(8);
                    assertThat((Integer) ReflectionTestUtils.getField(config, "queueCapacity")).isEqualTo(16);
                });
    }
}
