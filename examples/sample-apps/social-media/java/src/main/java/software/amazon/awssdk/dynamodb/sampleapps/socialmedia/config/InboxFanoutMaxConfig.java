package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Validates {@code dynamodb.inbox-fanout-max} at startup and exposes the accepted value.
 *
 * <p>The value is the {@code BatchWriteItem} chunk size for inbox fan-out. It is not a participant
 * cap. Absent means {@value #DEFAULT}. Values outside {@value #MIN} through {@value #MAX} fail
 * context refresh.
 */
@Configuration
public class InboxFanoutMaxConfig {

    public static final int DEFAULT = 25;

    public static final int MIN = 1;

    public static final int MAX = 25;

    public static final String OUT_OF_RANGE =
            "dynamodb.inbox-fanout-max must be between 1 and 25";

    /**
     * Fails context refresh before other beans are created when the chunk size is out of range.
     *
     * @param environment application environment
     * @return post-processor that validates the inbox fan-out chunk size
     */
    @Bean
    public static BeanFactoryPostProcessor inboxFanoutMaxStartupValidator(Environment environment) {
        return beanFactory -> validate(
                environment.getProperty("dynamodb.inbox-fanout-max", Integer.class, DEFAULT));
    }

    /**
     * Accepted inbox {@code BatchWriteItem} chunk size used by conversation create and message send.
     *
     * @param inboxFanoutMax {@code dynamodb.inbox-fanout-max}, default {@value #DEFAULT}
     * @return the validated chunk size
     */
    @Bean
    public InboxFanoutMax inboxFanoutMax(
            @Value("${dynamodb.inbox-fanout-max:" + DEFAULT + "}") int inboxFanoutMax) {
        return new InboxFanoutMax(validate(inboxFanoutMax));
    }

    /**
     * Accepts a missing value as {@value #DEFAULT} and rejects values outside {@value #MIN} through
     * {@value #MAX}.
     *
     * @param inboxFanoutMax configured chunk size, or {@code null} when the property is absent
     * @return the accepted chunk size
     */
    public static int validate(Integer inboxFanoutMax) {
        int value = inboxFanoutMax == null ? DEFAULT : inboxFanoutMax;
        if (value < MIN || value > MAX) {
            throw new IllegalStateException(OUT_OF_RANGE);
        }
        return value;
    }
}
