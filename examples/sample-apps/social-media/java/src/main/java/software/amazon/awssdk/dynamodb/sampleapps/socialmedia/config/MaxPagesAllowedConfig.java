package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Validates {@code dynamodb.max-pages-allowed} at startup and exposes the accepted value.
 *
 * <p>The cap bounds how many continuation tokens a client may walk on timeline and inbox reads. It
 * is not the per-request page size ({@code limit} 1 through 100). Absent means {@value #DEFAULT}.
 * Values outside {@value #MIN} through {@value #MAX} fail context refresh.
 */
@Configuration
public class MaxPagesAllowedConfig {

    public static final int DEFAULT = 100;

    public static final int MIN = 1;

    public static final int MAX = 10_000;

    public static final String OUT_OF_RANGE =
            "dynamodb.max-pages-allowed must be between 1 and 10000";

    /**
     * Fails context refresh before other beans are created when the cap is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates the pagination depth cap
     */
    @Bean
    public static BeanFactoryPostProcessor maxPagesAllowedStartupValidator(Environment environment) {
        return beanFactory -> validate(
                environment.getProperty("dynamodb.max-pages-allowed", Integer.class, DEFAULT));
    }

    /**
     * Accepted pagination depth cap shared by timeline and inbox reads.
     *
     * @param maxPagesAllowed {@code dynamodb.max-pages-allowed}, default {@value #DEFAULT}
     * @return the validated cap
     */
    @Bean
    public MaxPagesAllowed maxPagesAllowed(
            @Value("${dynamodb.max-pages-allowed:" + DEFAULT + "}") int maxPagesAllowed) {
        return new MaxPagesAllowed(validate(maxPagesAllowed));
    }

    /**
     * Accepts a missing cap as {@value #DEFAULT} and rejects values outside {@value #MIN} through
     * {@value #MAX}.
     *
     * @param maxPagesAllowed configured cap, or {@code null} when the property is absent
     * @return the accepted cap
     */
    public static int validate(Integer maxPagesAllowed) {
        int value = maxPagesAllowed == null ? DEFAULT : maxPagesAllowed;
        if (value < MIN || value > MAX) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
        return value;
    }
}
