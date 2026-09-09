package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.InboxFanoutMax;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.InboxFanoutMaxConfig;

/**
 * Unit coverage for inbox fan-out chunk size startup validation.
 *
 * <p>Exercises the accepted range, defaults, and out-of-range failures without Docker.
 * {@link ApplicationContextRunner} confirms Spring property binding for valid values.
 */
@Tag("unit")
class InboxFanoutMaxConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InboxFanoutMaxConfig.class);

    @Test
    void validate_whenNull_defaultsToTwentyFive() {
        assertThat(InboxFanoutMaxConfig.validate(null)).isEqualTo(InboxFanoutMaxConfig.DEFAULT);
    }

    @Test
    void validate_whenOne_returnsOne() {
        assertThat(InboxFanoutMaxConfig.validate(1)).isEqualTo(1);
    }

    @Test
    void validate_whenTwentyFive_returnsTwentyFive() {
        assertThat(InboxFanoutMaxConfig.validate(InboxFanoutMaxConfig.DEFAULT))
                .isEqualTo(InboxFanoutMaxConfig.DEFAULT);
    }

    @Test
    void validate_whenZero_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> InboxFanoutMaxConfig.validate(0))
                .withMessage(InboxFanoutMaxConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenTwentySix_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> InboxFanoutMaxConfig.validate(InboxFanoutMaxConfig.MAX + 1))
                .withMessage(InboxFanoutMaxConfig.OUT_OF_RANGE);
    }

    @Test
    void context_whenMaxIsAbsent_defaultsToTwentyFive() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(InboxFanoutMax.class).value())
                    .isEqualTo(InboxFanoutMaxConfig.DEFAULT);
        });
    }

    @Test
    void context_whenMaxIsOne_starts() {
        contextRunner.withPropertyValues("dynamodb.inbox-fanout-max=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InboxFanoutMax.class).value()).isEqualTo(1);
                });
    }

    @Test
    void context_whenMaxIsTwentyFive_starts() {
        contextRunner.withPropertyValues("dynamodb.inbox-fanout-max=25")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InboxFanoutMax.class).value())
                            .isEqualTo(InboxFanoutMaxConfig.MAX);
                });
    }

    @Test
    void context_whenMaxIsZero_fails() {
        contextRunner.withPropertyValues("dynamodb.inbox-fanout-max=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(InboxFanoutMaxConfig.OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenMaxIsTwentySix_fails() {
        contextRunner.withPropertyValues("dynamodb.inbox-fanout-max=26")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(InboxFanoutMaxConfig.OUT_OF_RANGE);
                });
    }
}
