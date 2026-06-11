package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.AsyncDelayConfig;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.AsyncSupport;

/**
 * Unit tests for {@link AsyncDelayConfig} bean wiring.
 */
@Tag("unit")
public class AsyncDelayConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncDelayConfig.class, AsyncSupport.class);

    @Test
    void asyncDelayScheduler_whenContextStarts_shouldRegisterRunnableBean() {
        contextRunner.run(context -> {
            assertThat(context).hasBean(AsyncDelayConfig.ASYNC_DELAY_SCHEDULER_BEAN);

            ScheduledExecutorService scheduler = context.getBean(
                    AsyncDelayConfig.ASYNC_DELAY_SCHEDULER_BEAN, ScheduledExecutorService.class);

            assertThat(scheduler).isNotNull();
            assertThat(scheduler.isShutdown()).isFalse();
        });
    }

    @Test
    void asyncSupport_whenContextStarts_shouldInjectDelayScheduler() {
        contextRunner.run(context -> {
            AsyncSupport asyncSupport = context.getBean(AsyncSupport.class);

            assertThat(asyncSupport).isNotNull();
            assertThat(asyncSupport.sleepMillis(0).join()).isNull();
        });
    }
}
