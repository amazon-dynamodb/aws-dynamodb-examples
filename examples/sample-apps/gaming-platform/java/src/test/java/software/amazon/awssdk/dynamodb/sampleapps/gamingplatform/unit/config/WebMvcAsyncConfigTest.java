package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.WebMvcAsyncConfig;

/**
 * Unit tests for {@link WebMvcAsyncConfig}.
 *
 * <p>Covers property binding (custom values and defaults) and the actual wiring performed by
 * {@link WebMvcAsyncConfig#configureAsyncSupport} so a regression in the pool sizing, thread-name
 * prefix, or async request timeout is caught.
 */
@Tag("unit")
class WebMvcAsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebMvcAsyncConfig.class);

    @Test
    void configureAsyncSupport_whenPropertiesSet_shouldBindPoolSizesAndTimeout() {
        contextRunner
                .withPropertyValues(
                        "spring.mvc.async.request-timeout=15000",
                        "gaming-platform.mvc.async.core-pool-size=7",
                        "gaming-platform.mvc.async.max-pool-size=21",
                        "gaming-platform.mvc.async.queue-capacity=42")
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

    @Test
    void configureAsyncSupport_whenInvoked_shouldWireBoundedExecutorAndTimeout() {
        WebMvcAsyncConfig config = new WebMvcAsyncConfig();
        ReflectionTestUtils.setField(config, "requestTimeoutMillis", 12_000L);
        ReflectionTestUtils.setField(config, "corePoolSize", 4);
        ReflectionTestUtils.setField(config, "maxPoolSize", 16);
        ReflectionTestUtils.setField(config, "queueCapacity", 25);

        AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);
        when(configurer.setTaskExecutor(any())).thenReturn(configurer);

        config.configureAsyncSupport(configurer);

        ArgumentCaptor<AsyncTaskExecutor> executorCaptor = ArgumentCaptor.forClass(AsyncTaskExecutor.class);
        verify(configurer).setTaskExecutor(executorCaptor.capture());
        verify(configurer).setDefaultTimeout(12_000L);

        assertThat(executorCaptor.getValue()).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) executorCaptor.getValue();
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(16);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("mvc-async-");
        // The executor is initialized, so its bounded work queue exposes the configured capacity.
        assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(25);
    }
}
