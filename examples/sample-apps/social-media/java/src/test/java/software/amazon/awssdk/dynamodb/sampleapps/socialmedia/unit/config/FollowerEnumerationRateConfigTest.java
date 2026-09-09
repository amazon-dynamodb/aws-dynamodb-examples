package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.FollowerEnumerationRate;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.FollowerEnumerationRateConfig;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.FollowerEnumerationLimiter;

/**
 * Unit coverage for follower enumeration rate startup validation.
 *
 * <p>Exercises the accepted range, defaults, and out-of-range failures without Docker.
 * {@link ApplicationContextRunner} confirms Spring property binding for valid values, and that
 * {@link FollowerEnumerationLimiter} constructs from the rate bean.
 */
@Tag("unit")
class FollowerEnumerationRateConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FollowerEnumerationRateConfig.class);

    @Test
    void validate_whenNull_defaultsToFifty() {
        assertThat(FollowerEnumerationRateConfig.validate(null))
                .isEqualTo(FollowerEnumerationRateConfig.DEFAULT_PERMITS_PER_SECOND);
    }

    @Test
    void validate_whenOne_returnsOne() {
        assertThat(FollowerEnumerationRateConfig.validate(1)).isEqualTo(1);
    }

    @Test
    void validate_whenDefault_returnsFifty() {
        assertThat(FollowerEnumerationRateConfig.validate(50)).isEqualTo(50);
    }

    @Test
    void validate_whenMax_returnsMax() {
        assertThat(FollowerEnumerationRateConfig.validate(FollowerEnumerationRateConfig.MAX_PERMITS_PER_SECOND))
                .isEqualTo(FollowerEnumerationRateConfig.MAX_PERMITS_PER_SECOND);
    }

    @Test
    void validate_whenZero_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FollowerEnumerationRateConfig.validate(0))
                .withMessage(FollowerEnumerationRateConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenNegative_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FollowerEnumerationRateConfig.validate(-1))
                .withMessage(FollowerEnumerationRateConfig.OUT_OF_RANGE);
    }

    @Test
    void validate_whenAboveMax_failsFast() {
        assertThatIllegalStateException()
                .isThrownBy(() -> FollowerEnumerationRateConfig.validate(
                        FollowerEnumerationRateConfig.MAX_PERMITS_PER_SECOND + 1))
                .withMessage(FollowerEnumerationRateConfig.OUT_OF_RANGE);
    }

    @Test
    void context_whenRateIsAbsent_defaultsToFifty() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FollowerEnumerationRate.class).value())
                    .isEqualTo(FollowerEnumerationRateConfig.DEFAULT_PERMITS_PER_SECOND);
        });
    }

    @Test
    void context_whenLimiterIsRegistered_constructsFromRate() {
        contextRunner.withUserConfiguration(FollowerEnumerationLimiter.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FollowerEnumerationLimiter.class);
                });
    }

    @Test
    void context_whenRateIsTwo_starts() {
        contextRunner.withPropertyValues("dynamodb.follower-enumeration-permits-per-second=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(FollowerEnumerationRate.class).value()).isEqualTo(2);
                });
    }

    @Test
    void context_whenRateIsZero_fails() {
        contextRunner.withPropertyValues("dynamodb.follower-enumeration-permits-per-second=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(FollowerEnumerationRateConfig.OUT_OF_RANGE);
                });
    }

    @Test
    void context_whenRateIsAboveMax_fails() {
        contextRunner.withPropertyValues("dynamodb.follower-enumeration-permits-per-second=10001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(FollowerEnumerationRateConfig.OUT_OF_RANGE);
                });
    }
}
