package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.AsyncMinimumVisibleDelay;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.TimelineFanoutConfig;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.TimelineFanoutMode;

/**
 * Unit coverage for timeline fan-out startup validation.
 *
 * <p>Exercises the complete {@code SYNC}/{@code ASYNC} and streams-enabled matrix, plus unknown
 * mode values, without Docker. Also covers {@code dynamodb.async-minimum-visible-delay} defaults
 * and out-of-range fail-fast. {@link ApplicationContextRunner} confirms Spring property binding
 * for the valid combinations.
 */
@Tag("unit")
class TimelineFanoutConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TimelineFanoutConfig.class);

    @Test
    void validate_whenSyncAndStreamsEnabled_returnsSync() {
        assertThat(TimelineFanoutConfig.validate("SYNC", true)).isEqualTo(TimelineFanoutMode.SYNC);
    }

    @Test
    void validate_whenSyncAndStreamsDisabled_returnsSync() {
        assertThat(TimelineFanoutConfig.validate("SYNC", false)).isEqualTo(TimelineFanoutMode.SYNC);
    }

    @Test
    void validate_whenAsyncAndStreamsEnabled_returnsAsync() {
        assertThat(TimelineFanoutConfig.validate("ASYNC", true)).isEqualTo(TimelineFanoutMode.ASYNC);
    }

    @Test
    void validate_whenAsyncAndStreamsDisabled_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutConfig.validate("ASYNC", false))
                .withMessage(TimelineFanoutConfig.ASYNC_REQUIRES_STREAMS);
    }

    @Test
    void validate_whenModeIsLowercaseAsync_returnsAsync() {
        assertThat(TimelineFanoutConfig.validate("async", true)).isEqualTo(TimelineFanoutMode.ASYNC);
    }

    @Test
    void validate_whenModeIsUnknown_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutConfig.validate("BATCH", true))
                .withMessage("dynamodb.timeline-fanout-mode must be SYNC or ASYNC");
    }

    @Test
    void validate_whenModeIsBlank_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutConfig.validate("  ", true))
                .withMessage("dynamodb.timeline-fanout-mode must be SYNC or ASYNC");
    }

    @Test
    void context_whenSyncAndStreamsDisabled_starts() {
        contextRunner.withPropertyValues(
                        "dynamodb.timeline-fanout-mode=SYNC",
                        "dynamodb.streams.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMode.class)).isEqualTo(TimelineFanoutMode.SYNC);
                });
    }

    @Test
    void context_whenSyncAndStreamsEnabled_starts() {
        contextRunner.withPropertyValues(
                        "dynamodb.timeline-fanout-mode=SYNC",
                        "dynamodb.streams.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMode.class)).isEqualTo(TimelineFanoutMode.SYNC);
                });
    }

    @Test
    void context_whenAsyncAndStreamsEnabled_starts() {
        contextRunner.withPropertyValues(
                        "dynamodb.timeline-fanout-mode=ASYNC",
                        "dynamodb.streams.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMode.class)).isEqualTo(TimelineFanoutMode.ASYNC);
                });
    }

    @Test
    void context_whenAsyncAndStreamsDisabled_fails() {
        contextRunner.withPropertyValues(
                        "dynamodb.timeline-fanout-mode=ASYNC",
                        "dynamodb.streams.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(TimelineFanoutConfig.ASYNC_REQUIRES_STREAMS);
                });
    }

    @Test
    void context_whenModeIsAbsentAndStreamsDisabled_defaultsToSync() {
        contextRunner.withPropertyValues("dynamodb.streams.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMode.class)).isEqualTo(TimelineFanoutMode.SYNC);
                    assertThat(context.getBean(AsyncMinimumVisibleDelay.class).value())
                            .isEqualTo(TimelineFanoutConfig.DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS);
                });
    }

    @Test
    void validateAsyncMinimumVisibleDelay_whenNull_defaultsToTwo() {
        assertThat(TimelineFanoutConfig.validateAsyncMinimumVisibleDelay(null))
                .isEqualTo(TimelineFanoutConfig.DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS);
    }

    @Test
    void validateAsyncMinimumVisibleDelay_whenOne_returnsOne() {
        assertThat(TimelineFanoutConfig.validateAsyncMinimumVisibleDelay(1)).isEqualTo(1);
    }

    @Test
    void validateAsyncMinimumVisibleDelay_whenSixty_returnsSixty() {
        assertThat(TimelineFanoutConfig.validateAsyncMinimumVisibleDelay(
                TimelineFanoutConfig.MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS))
                .isEqualTo(TimelineFanoutConfig.MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS);
    }

    @Test
    void validateAsyncMinimumVisibleDelay_whenZero_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutConfig.validateAsyncMinimumVisibleDelay(0))
                .withMessage(TimelineFanoutConfig.ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE);
    }

    @Test
    void validateAsyncMinimumVisibleDelay_whenSixtyOne_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutConfig.validateAsyncMinimumVisibleDelay(
                        TimelineFanoutConfig.MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS + 1))
                .withMessage(TimelineFanoutConfig.ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE);
    }

    @Test
    void context_whenDelayIsAbsent_defaultsToTwo() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AsyncMinimumVisibleDelay.class).value())
                    .isEqualTo(TimelineFanoutConfig.DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS);
        });
    }

    @Test
    void context_whenDelayIsOne_starts() {
        contextRunner.withPropertyValues("dynamodb.async-minimum-visible-delay=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsyncMinimumVisibleDelay.class).value()).isEqualTo(1);
                });
    }

    @Test
    void context_whenDelayIsSixty_starts() {
        contextRunner.withPropertyValues("dynamodb.async-minimum-visible-delay=60")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AsyncMinimumVisibleDelay.class).value())
                            .isEqualTo(TimelineFanoutConfig.MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS);
                });
    }

    @Test
    void context_whenDelayIsZero_fails() {
        contextRunner.withPropertyValues("dynamodb.async-minimum-visible-delay=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(TimelineFanoutConfig.ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenDelayIsSixtyOne_fails() {
        contextRunner.withPropertyValues("dynamodb.async-minimum-visible-delay=61")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(TimelineFanoutConfig.ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE);
                });
    }
}
