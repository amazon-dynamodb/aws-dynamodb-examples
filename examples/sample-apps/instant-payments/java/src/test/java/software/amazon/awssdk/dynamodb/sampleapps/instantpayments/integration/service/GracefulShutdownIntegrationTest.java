package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.DynamoDbStreamsPaymentEventListener;

/**
 * Verifies the graceful-shutdown contract: stopping the streams poller drains its current pass and
 * returns well inside the configured {@code spring.lifecycle.timeout-per-shutdown-phase} window instead of
 * pinning the thread until the operating-system socket timeout.
 *
 * <p>This drives {@link DynamoDbStreamsPaymentEventListener#stop()} directly (the same call Spring makes on a
 * SIGTERM before closing the context) and asserts it returns inside the shutdown window. The class context is
 * dirtied after the run, so stopping the shared poller has no effect on other test classes.
 */
@Tag("integration")
public class GracefulShutdownIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbStreamsPaymentEventListener listener;

    @Value("${spring.lifecycle.timeout-per-shutdown-phase}")
    private Duration shutdownPhaseTimeout;

    @Test
    void stop_whenPollerRunning_shouldReturnWithinShutdownWindow() {
        assertThat(listener.isRunning()).isTrue();

        long startNanos = System.nanoTime();
        listener.stop();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        assertThat(listener.isRunning()).isFalse();
        assertThat(elapsed)
                .as("poller stop drains within the configured shutdown window")
                .isLessThanOrEqualTo(shutdownPhaseTimeout);
    }
}
