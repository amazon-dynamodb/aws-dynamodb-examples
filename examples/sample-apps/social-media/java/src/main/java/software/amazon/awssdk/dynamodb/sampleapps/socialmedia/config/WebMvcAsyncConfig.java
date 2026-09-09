package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures Spring MVC async request handling for controllers that return {@code CompletableFuture}.
 *
 * <p>Releases Tomcat worker threads while DynamoDB and S3 I/O runs on the SDK async clients.
 * Completion and response writing are dispatched on a bounded {@code mvc-async-*} thread pool
 * instead of an unbounded default executor.
 *
 * <p>The pool sizing (core {@value #DEFAULT_CORE_POOL_SIZE}, max {@value #DEFAULT_MAX_POOL_SIZE},
 * queue {@value #DEFAULT_QUEUE_CAPACITY}) and the async request timeout
 * ({@value #DEFAULT_REQUEST_TIMEOUT_MILLIS} ms) are the observable contract. The request timeout
 * stays above {@link DynamoDbConfig#API_CALL_TIMEOUT} so Spring does not abort the async request
 * before the SDK finishes or returns a mapped error.
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10000;

    public static final int DEFAULT_CORE_POOL_SIZE = 10;

    public static final int DEFAULT_MAX_POOL_SIZE = 50;

    public static final int DEFAULT_QUEUE_CAPACITY = 100;

    @Value("${spring.mvc.async.request-timeout:" + DEFAULT_REQUEST_TIMEOUT_MILLIS + "}")
    private long requestTimeoutMillis;

    @Value("${social-media.mvc.async.core-pool-size:" + DEFAULT_CORE_POOL_SIZE + "}")
    private int corePoolSize;

    @Value("${social-media.mvc.async.max-pool-size:" + DEFAULT_MAX_POOL_SIZE + "}")
    private int maxPoolSize;

    @Value("${social-media.mvc.async.queue-capacity:" + DEFAULT_QUEUE_CAPACITY + "}")
    private int queueCapacity;

    /**
     * Wires a bounded executor and the async request timeout used by {@code CompletableFuture}
     * controllers.
     *
     * @param configurer Spring MVC async support configurer
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("mvc-async-");
        executor.initialize();
        configurer.setTaskExecutor(executor);
        configurer.setDefaultTimeout(requestTimeoutMillis);
    }
}
