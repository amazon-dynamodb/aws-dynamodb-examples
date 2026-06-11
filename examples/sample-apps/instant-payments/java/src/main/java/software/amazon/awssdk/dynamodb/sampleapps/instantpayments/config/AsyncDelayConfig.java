package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.AsyncSupport;

/**
 * Provides a dedicated scheduler for async retry backoffs in service-layer {@code CompletableFuture} chains.
 *
 * <p>Kept separate from the {@code mvc-async-*} servlet pool and from the SDK Netty client so processor
 * delay work does not compete with HTTP response dispatch or DynamoDB I/O threads.
 */
@Configuration
public class AsyncDelayConfig {

    /**
     * Spring bean name for the delay scheduler injected into {@link AsyncSupport}.
     */
    public static final String ASYNC_DELAY_SCHEDULER_BEAN = "asyncDelayScheduler";

    /**
     * Single-threaded scheduler for bounded backoff delays ({@code async-delay-*} thread names).
     *
     * @return daemon scheduler shut down with the application context
     */
    @Bean(name = ASYNC_DELAY_SCHEDULER_BEAN, destroyMethod = "shutdown")
    public ScheduledExecutorService asyncDelayScheduler() {
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "async-delay-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
