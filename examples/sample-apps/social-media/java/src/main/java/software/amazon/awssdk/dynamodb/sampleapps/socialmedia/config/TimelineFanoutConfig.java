package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Parses {@code dynamodb.timeline-fanout-mode} once and rejects an unusable combination at startup.
 *
 * <p>{@code ASYNC} defers timeline materialization to the in-process stream consumer, so it requires
 * {@code dynamodb.streams.enabled=true} (absent means enabled). {@code SYNC} is valid with the
 * poller on or off.
 *
 * <p>Also binds {@code dynamodb.async-minimum-visible-delay}, the operator expectation in seconds
 * for how long {@code ASYNC} home-feed rows may take to appear. Absent means
 * {@value #DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS}. Values outside
 * {@value #MIN_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS} through
 * {@value #MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS} fail context refresh. The value is not a
 * request-path sleep.
 */
@Configuration
public class TimelineFanoutConfig {

    public static final String ASYNC_REQUIRES_STREAMS =
            "dynamodb.timeline-fanout-mode=ASYNC requires dynamodb.streams.enabled=true so the "
                    + "in-process stream consumer can write timeline rows. Set dynamodb.streams.enabled=true, "
                    + "or set dynamodb.timeline-fanout-mode=SYNC.";

    public static final int DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS = 2;

    public static final int MIN_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS = 1;

    public static final int MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS = 60;

    public static final String ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE =
            "dynamodb.async-minimum-visible-delay must be between 1 and 60";

    /**
     * Fails context refresh before other beans are created when {@code ASYNC} is paired with a
     * disabled streams poller, or when the visibility delay is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates fan-out mode against the streams flag and the delay
     */
    @Bean
    public static BeanFactoryPostProcessor timelineFanoutStartupValidator(Environment environment) {
        return beanFactory -> {
            validate(
                    environment.getProperty("dynamodb.timeline-fanout-mode", "SYNC"),
                    environment.getProperty("dynamodb.streams.enabled", Boolean.class, Boolean.TRUE));
            validateAsyncMinimumVisibleDelay(environment.getProperty(
                    "dynamodb.async-minimum-visible-delay",
                    Integer.class,
                    DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS));
        };
    }

    /**
     * Canonical fan-out mode shared by publish and stream projection.
     *
     * @param fanoutMode      {@code dynamodb.timeline-fanout-mode}, default {@code SYNC}
     * @param streamsEnabled  {@code dynamodb.streams.enabled}, default {@code true}
     * @return the parsed mode after the streams dependency is checked
     */
    @Bean
    public TimelineFanoutMode timelineFanoutMode(
            @Value("${dynamodb.timeline-fanout-mode:SYNC}") String fanoutMode,
            @Value("${dynamodb.streams.enabled:true}") boolean streamsEnabled) {
        return validate(fanoutMode, streamsEnabled);
    }

    /**
     * Accepted {@code ASYNC} visibility delay used as an operator expectation, not a sleep.
     *
     * @param asyncMinimumVisibleDelay {@code dynamodb.async-minimum-visible-delay}, default
     *                                 {@value #DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS}
     * @return the validated delay in seconds
     */
    @Bean
    public AsyncMinimumVisibleDelay asyncMinimumVisibleDelay(
            @Value("${dynamodb.async-minimum-visible-delay:"
                    + DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS + "}")
            int asyncMinimumVisibleDelay) {
        return new AsyncMinimumVisibleDelay(validateAsyncMinimumVisibleDelay(asyncMinimumVisibleDelay));
    }

    /**
     * Parses the mode and rejects {@code ASYNC} when the streams poller is disabled.
     *
     * @param fanoutMode     configured mode
     * @param streamsEnabled whether the in-process poller is enabled
     * @return the canonical mode
     */
    public static TimelineFanoutMode validate(String fanoutMode, boolean streamsEnabled) {
        TimelineFanoutMode mode = TimelineFanoutMode.fromProperty(fanoutMode);
        if (mode == TimelineFanoutMode.ASYNC && !streamsEnabled) {
            throw new IllegalStateException(ASYNC_REQUIRES_STREAMS);
        }
        return mode;
    }

    /**
     * Accepts a missing delay as {@value #DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS} and rejects
     * values outside {@value #MIN_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS} through
     * {@value #MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS}.
     *
     * @param asyncMinimumVisibleDelay configured delay in seconds, or {@code null} when absent
     * @return the accepted delay in seconds
     */
    public static int validateAsyncMinimumVisibleDelay(Integer asyncMinimumVisibleDelay) {
        int value = asyncMinimumVisibleDelay == null
                ? DEFAULT_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS
                : asyncMinimumVisibleDelay;
        if (value < MIN_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS
                || value > MAX_ASYNC_MINIMUM_VISIBLE_DELAY_SECONDS) {
            throw new IllegalStateException(ASYNC_MINIMUM_VISIBLE_DELAY_OUT_OF_RANGE);
        }
        return value;
    }
}
