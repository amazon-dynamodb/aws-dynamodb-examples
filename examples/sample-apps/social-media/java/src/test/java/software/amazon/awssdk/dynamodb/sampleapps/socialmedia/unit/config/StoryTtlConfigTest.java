package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.StoryTtl;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.StoryTtlConfig;

/**
 * Unit coverage for story TTL startup validation.
 *
 * <p>Exercises the accepted range, defaults, and out-of-range failures without Docker.
 * {@link ApplicationContextRunner} confirms Spring property binding for valid values.
 */
@Tag("unit")
class StoryTtlConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(StoryTtlConfig.class);

    @Test
    void validate_whenNull_defaultsToEightySixThousandFourHundred() {
        assertThat(StoryTtlConfig.validate(null)).isEqualTo(StoryTtlConfig.DEFAULT);
    }

    @Test
    void validate_whenMin_returnsMin() {
        assertThat(StoryTtlConfig.validate(StoryTtlConfig.MIN)).isEqualTo(StoryTtlConfig.MIN);
    }

    @Test
    void validate_whenDefault_returnsDefault() {
        assertThat(StoryTtlConfig.validate(StoryTtlConfig.DEFAULT)).isEqualTo(StoryTtlConfig.DEFAULT);
    }

    @Test
    void validate_whenMax_returnsMax() {
        assertThat(StoryTtlConfig.validate(StoryTtlConfig.MAX)).isEqualTo(StoryTtlConfig.MAX);
    }

    @Test
    void validate_whenBelowMin_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> StoryTtlConfig.validate(StoryTtlConfig.MIN - 1))
                .withMessage(StoryTtlConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenAboveMax_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> StoryTtlConfig.validate(StoryTtlConfig.MAX + 1))
                .withMessage(StoryTtlConfig.OUT_OF_RANGE);
    }

    @Test
    void context_whenTtlIsAbsent_defaultsToEightySixThousandFourHundred() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StoryTtl.class).value()).isEqualTo(StoryTtlConfig.DEFAULT);
        });
    }

    @Test
    void context_whenTtlIsMin_starts() {
        contextRunner.withPropertyValues("dynamodb.story-ttl-seconds=" + StoryTtlConfig.MIN)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StoryTtl.class).value()).isEqualTo(StoryTtlConfig.MIN);
                });
    }

    @Test
    void context_whenTtlIsBelowMin_fails() {
        contextRunner.withPropertyValues("dynamodb.story-ttl-seconds=" + (StoryTtlConfig.MIN - 1))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(StoryTtlConfig.OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenTtlIsAboveMax_fails() {
        contextRunner.withPropertyValues("dynamodb.story-ttl-seconds=" + (StoryTtlConfig.MAX + 1))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(StoryTtlConfig.OUT_OF_RANGE);
                });
    }
}
