package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Validates {@code dynamodb.timeline-fanout-max} at startup and exposes the accepted value.
 *
 * <p>The value is the {@code BatchWriteItem} chunk size for timeline fan-out. It is not a recipient
 * cap. Absent means {@value #DEFAULT}. Values outside {@value #MIN} through {@value #MAX} fail
 * context refresh.
 */
@Configuration
public class TimelineFanoutMaxConfig {

    public static final int DEFAULT = 25;

    public static final int MIN = 1;

    public static final int MAX = 25;

    public static final String OUT_OF_RANGE =
            "dynamodb.timeline-fanout-max must be between 1 and 25";

    /**
     * Fails context refresh before other beans are created when the chunk size is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates the timeline fan-out chunk size
     */
    @Bean
    public static BeanFactoryPostProcessor timelineFanoutMaxStartupValidator(Environment environment) {
        return beanFactory -> validate(
                environment.getProperty("dynamodb.timeline-fanout-max", Integer.class, DEFAULT));
    }

    /**
     * Accepted timeline {@code BatchWriteItem} chunk size used by publish and stream fan-out.
     *
     * @param timelineFanoutMax {@code dynamodb.timeline-fanout-max}, default {@value #DEFAULT}
     * @return the validated chunk size
     */
    @Bean
    public TimelineFanoutMax timelineFanoutMax(
            @Value("${dynamodb.timeline-fanout-max:" + DEFAULT + "}") int timelineFanoutMax) {
        return new TimelineFanoutMax(validate(timelineFanoutMax));
    }

    /**
     * Accepts a missing value as {@value #DEFAULT} and rejects values outside {@value #MIN} through
     * {@value #MAX}.
     *
     * @param timelineFanoutMax configured chunk size, or {@code null} when the property is absent
     * @return the accepted chunk size
     */
    public static int validate(Integer timelineFanoutMax) {
        int value = timelineFanoutMax == null ? DEFAULT : timelineFanoutMax;
        if (value < MIN || value > MAX) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
        return value;
    }
}
