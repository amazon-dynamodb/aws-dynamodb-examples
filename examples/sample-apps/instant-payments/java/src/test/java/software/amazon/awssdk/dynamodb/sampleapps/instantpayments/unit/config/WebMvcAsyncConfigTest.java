package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.WebMvcAsyncConfig;

/**
 * Unit tests for {@link WebMvcAsyncConfig} property binding.
 */
@Tag("unit")
public class WebMvcAsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebMvcAsyncConfig.class);

    @Test
    void configureAsyncSupport_whenPropertiesSet_shouldBindPoolSizesAndTimeout() {
        contextRunner
                .withPropertyValues(
                        "spring.mvc.async.request-timeout=15000",
                        "instant-payments.mvc.async.core-pool-size=7",
                        "instant-payments.mvc.async.max-pool-size=21",
                        "instant-payments.mvc.async.queue-capacity=42")
                .run(context -> {
                    WebMvcAsyncConfig config = context.getBean(WebMvcAsyncConfig.class);
                    assertThat(ReflectionTestUtils.getField(config, "requestTimeoutMillis")).isEqualTo(15_000L);
                    assertThat(ReflectionTestUtils.getField(config, "corePoolSize")).isEqualTo(7);
                    assertThat(ReflectionTestUtils.getField(config, "maxPoolSize")).isEqualTo(21);
                    assertThat(ReflectionTestUtils.getField(config, "queueCapacity")).isEqualTo(42);
                });
    }

    @Test
    void configureAsyncSupport_whenPropertiesOmitted_shouldUseDefaults() {
        contextRunner.run(context -> {
            WebMvcAsyncConfig config = context.getBean(WebMvcAsyncConfig.class);
            assertThat(ReflectionTestUtils.getField(config, "requestTimeoutMillis")).isEqualTo(10_000L);
            assertThat(ReflectionTestUtils.getField(config, "corePoolSize")).isEqualTo(10);
            assertThat(ReflectionTestUtils.getField(config, "maxPoolSize")).isEqualTo(50);
            assertThat(ReflectionTestUtils.getField(config, "queueCapacity")).isEqualTo(100);
        });
    }
}
