package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.MaxPagesAllowed;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.MaxPagesAllowedConfig;

/**
 * Unit coverage for pagination depth cap startup validation.
 *
 * <p>Exercises the accepted range, defaults, and out-of-range failures without Docker.
 * {@link ApplicationContextRunner} confirms Spring property binding for valid values.
 */
@Tag("unit")
class MaxPagesAllowedConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MaxPagesAllowedConfig.class);

    @Test
    void validate_whenNull_defaultsToOneHundred() {
        assertThat(MaxPagesAllowedConfig.validate(null)).isEqualTo(MaxPagesAllowedConfig.DEFAULT);
    }

    @Test
    void validate_whenOne_returnsOne() {
        assertThat(MaxPagesAllowedConfig.validate(1)).isEqualTo(1);
    }

    @Test
    void validate_whenDefault_returnsOneHundred() {
        assertThat(MaxPagesAllowedConfig.validate(100)).isEqualTo(100);
    }

    @Test
    void validate_whenMax_returnsMax() {
        assertThat(MaxPagesAllowedConfig.validate(MaxPagesAllowedConfig.MAX))
                .isEqualTo(MaxPagesAllowedConfig.MAX);
    }

    @Test
    void validate_whenZero_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> MaxPagesAllowedConfig.validate(0))
                .withMessage(MaxPagesAllowedConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenNegative_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> MaxPagesAllowedConfig.validate(-1))
                .withMessage(MaxPagesAllowedConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenAboveMax_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> MaxPagesAllowedConfig.validate(MaxPagesAllowedConfig.MAX + 1))
                .withMessage(MaxPagesAllowedConfig.OUT_OF_RANGE);
    }

    @Test
    void context_whenCapIsAbsent_defaultsToOneHundred() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MaxPagesAllowed.class).value())
                    .isEqualTo(MaxPagesAllowedConfig.DEFAULT);
        });
    }

    @Test
    void context_whenCapIsTwo_starts() {
        contextRunner.withPropertyValues("dynamodb.max-pages-allowed=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MaxPagesAllowed.class).value()).isEqualTo(2);
                });
    }

    @Test
    void context_whenCapIsZero_fails() {
        contextRunner.withPropertyValues("dynamodb.max-pages-allowed=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(MaxPagesAllowedConfig.OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenCapIsAboveMax_fails() {
        contextRunner.withPropertyValues("dynamodb.max-pages-allowed=10001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(MaxPagesAllowedConfig.OUT_OF_RANGE);
                });
    }
}
