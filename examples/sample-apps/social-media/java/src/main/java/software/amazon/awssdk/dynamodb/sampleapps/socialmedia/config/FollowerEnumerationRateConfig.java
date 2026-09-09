package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Validates {@code dynamodb.follower-enumeration-permits-per-second} at startup and exposes the
 * accepted value.
 *
 * <p>The rate bounds how often {@code PUBLIC} follower enumeration may Query a creator partition. It
 * is not {@code dynamodb.follower-fanout-cap}, which bounds rows per call. Absent means
 * {@value #DEFAULT_PERMITS_PER_SECOND}. Values outside {@value #MIN_PERMITS_PER_SECOND} through
 * {@value #MAX_PERMITS_PER_SECOND} fail context refresh.
 */
@Configuration
public class FollowerEnumerationRateConfig {

    public static final int DEFAULT_PERMITS_PER_SECOND = 50;

    public static final int MIN_PERMITS_PER_SECOND = 1;

    public static final int MAX_PERMITS_PER_SECOND = 10_000;

    public static final String OUT_OF_RANGE =
            "dynamodb.follower-enumeration-permits-per-second must be between 1 and 10000";

    /**
     * Fails context refresh before other beans are created when the rate is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates the follower enumeration rate
     */
    @Bean
    public static BeanFactoryPostProcessor followerEnumerationRateStartupValidator(Environment environment) {
        return beanFactory -> validate(environment.getProperty(
                "dynamodb.follower-enumeration-permits-per-second", Integer.class, DEFAULT_PERMITS_PER_SECOND));
    }

    /**
     * Accepted follower enumeration rate shared by both UserGraph repository implementations.
     *
     * @param permitsPerSecond {@code dynamodb.follower-enumeration-permits-per-second}, default
     *                         {@value #DEFAULT_PERMITS_PER_SECOND}
     * @return the validated rate
     */
    @Bean
    public FollowerEnumerationRate followerEnumerationRate(
            @Value("${dynamodb.follower-enumeration-permits-per-second:" + DEFAULT_PERMITS_PER_SECOND + "}")
            int permitsPerSecond) {
        return new FollowerEnumerationRate(validate(permitsPerSecond));
    }

    /**
     * Accepts a missing rate as {@value #DEFAULT_PERMITS_PER_SECOND} and rejects values outside
     * {@value #MIN_PERMITS_PER_SECOND} through {@value #MAX_PERMITS_PER_SECOND}.
     *
     * @param permitsPerSecond configured rate, or {@code null} when the property is absent
     * @return the accepted rate
     */
    public static int validate(Integer permitsPerSecond) {
        int value = permitsPerSecond == null ? DEFAULT_PERMITS_PER_SECOND : permitsPerSecond;
        if (value < MIN_PERMITS_PER_SECOND || value > MAX_PERMITS_PER_SECOND) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
        return value;
    }
}
