package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.TimelineFanoutMax;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.TimelineFanoutMaxConfig;

/**
 * Unit coverage for timeline fan-out chunk size startup validation.
 *
 * <p>Exercises the accepted range, defaults, and out-of-range failures without Docker.
 * {@link ApplicationContextRunner} confirms Spring property binding for valid values.
 */
@Tag("unit")
class TimelineFanoutMaxConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TimelineFanoutMaxConfig.class);

    @Test
    void validate_whenNull_defaultsToTwentyFive() {
        assertThat(TimelineFanoutMaxConfig.validate(null)).isEqualTo(TimelineFanoutMaxConfig.DEFAULT);
    }

    @Test
    void validate_whenOne_returnsOne() {
        assertThat(TimelineFanoutMaxConfig.validate(1)).isEqualTo(1);
    }

    @Test
    void validate_whenTwentyFive_returnsTwentyFive() {
        assertThat(TimelineFanoutMaxConfig.validate(TimelineFanoutMaxConfig.DEFAULT))
                .isEqualTo(TimelineFanoutMaxConfig.DEFAULT);
    }

    @Test
    void validate_whenZero_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutMaxConfig.validate(0))
                .withMessage(TimelineFanoutMaxConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenTwentySix_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> TimelineFanoutMaxConfig.validate(TimelineFanoutMaxConfig.MAX + 1))
                .withMessage(TimelineFanoutMaxConfig.OUT_OF_RANGE);
    }

    @Test
    void context_whenMaxIsAbsent_defaultsToTwentyFive() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TimelineFanoutMax.class).value())
                    .isEqualTo(TimelineFanoutMaxConfig.DEFAULT);
        });
    }

    @Test
    void context_whenMaxIsOne_starts() {
        contextRunner.withPropertyValues("dynamodb.timeline-fanout-max=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMax.class).value()).isEqualTo(1);
                });
    }

    @Test
    void context_whenMaxIsTwentyFive_starts() {
        contextRunner.withPropertyValues("dynamodb.timeline-fanout-max=25")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TimelineFanoutMax.class).value())
                            .isEqualTo(TimelineFanoutMaxConfig.MAX);
                });
    }

    @Test
    void context_whenMaxIsZero_fails() {
        contextRunner.withPropertyValues("dynamodb.timeline-fanout-max=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(TimelineFanoutMaxConfig.OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenMaxIsTwentySix_fails() {
        contextRunner.withPropertyValues("dynamodb.timeline-fanout-max=26")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(TimelineFanoutMaxConfig.OUT_OF_RANGE);
                });
    }
}
