package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures Spring MVC async request handling for controllers that return {@code CompletableFuture}.
 *
 * <p>Releases Tomcat worker threads while DynamoDB I/O runs on the SDK Netty client. Completion and
 * response writing are dispatched on a bounded {@code mvc-async-*} thread pool instead of Spring's
 * default {@code SimpleAsyncTaskExecutor}, which would create an unbounded thread per request.
 *
 * <p>{@code request-timeout} must stay above {@link DynamoDbConfig#API_CALL_TIMEOUT} so Spring does not
 * abort the async request before the SDK can finish or return a mapped error. See
 * {@code spring.mvc.async.request-timeout} in {@code application.yml}.
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    /** Async request timeout in milliseconds from {@code spring.mvc.async.request-timeout}. */
    @Value("${spring.mvc.async.request-timeout:10000}")
    private long requestTimeoutMillis;

    /** Core pool size for the {@code mvc-async-*} executor. */
    @Value("${instant-payments.mvc.async.core-pool-size:10}")
    private int corePoolSize;

    /** Maximum pool size for the {@code mvc-async-*} executor. */
    @Value("${instant-payments.mvc.async.max-pool-size:50}")
    private int maxPoolSize;

    /** Bounded queue capacity before async tasks are rejected. */
    @Value("${instant-payments.mvc.async.queue-capacity:100}")
    private int queueCapacity;

    /**
     * Wires a bounded executor and the async request timeout used by {@code CompletableFuture} controllers.
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
