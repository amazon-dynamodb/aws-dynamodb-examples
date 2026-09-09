package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Validates {@code dynamodb.story-ttl-seconds} at startup and exposes the accepted value.
 *
 * <p>The value is added to a story {@code createdAt} to populate {@code expiresAt}. Absent means
 * {@value #DEFAULT}. Values outside {@value #MIN} through {@value #MAX} fail context refresh.
 */
@Configuration
public class StoryTtlConfig {

    public static final long DEFAULT = 86_400L;

    public static final long MIN = 3_600L;

    public static final long MAX = 604_800L;

    public static final String OUT_OF_RANGE =
            "dynamodb.story-ttl-seconds must be between 3600 and 604800";

    /**
     * Fails context refresh before other beans are created when the story TTL is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates the story TTL
     */
    @Bean
    public static BeanFactoryPostProcessor storyTtlStartupValidator(Environment environment) {
        return beanFactory -> validate(
                environment.getProperty("dynamodb.story-ttl-seconds", Long.class, DEFAULT));
    }

    /**
     * Accepted story TTL shared by post publication.
     *
     * @param storyTtlSeconds {@code dynamodb.story-ttl-seconds}, default {@value #DEFAULT}
     * @return the validated TTL
     */
    @Bean
    public StoryTtl storyTtl(
            @Value("${dynamodb.story-ttl-seconds:" + DEFAULT + "}") long storyTtlSeconds) {
        return new StoryTtl(validate(storyTtlSeconds));
    }

    /**
     * Accepts a missing TTL as {@value #DEFAULT} and rejects values outside {@value #MIN} through
     * {@value #MAX}.
     *
     * @param storyTtlSeconds configured TTL, or {@code null} when the property is absent
     * @return the accepted TTL
     */
    public static long validate(Long storyTtlSeconds) {
        long value = storyTtlSeconds == null ? DEFAULT : storyTtlSeconds;
        if (value < MIN || value > MAX) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
        return value;
    }
}
