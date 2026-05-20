package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbConfig;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/**
 * Shared utilities for {@code BatchGetItem} retry orchestration when DynamoDB returns unprocessed keys.
 *
 * <p><strong>Why this exists.</strong> A {@code BatchGetItem} call can succeed with HTTP 200 while
 * returning {@code UnprocessedKeys}. The AWS SDK does not automatically retry that outcome because
 * the service call completed. Callers must submit the remaining keys again after a delay.
 *
 * <p><strong>Typical usage.</strong> After each {@code BatchGetItem} response, merge returned items
 * into a map. If {@code unprocessedKeys} is empty, you are done. Otherwise compute a delay with
 * {@link #unprocessedKeysDelay(int)}, wait with {@link #delayAsync(Duration)}, then call
 * {@code BatchGetItem} again with only the unprocessed key set. Stop after
 * {@link #MAX_UNPROCESSED_RETRIES} retries and return partial data if keys remain unprocessed.
 *
 * <p><strong>Example shape (pseudocode).</strong>
 * <pre>{@code
 * Map<String, KeysAndAttributes> request = buildKeysForFirstCall();
 * Map<String, YourItem> found = new HashMap<>();
 * for (int attempt = 0; attempt <= MAX_UNPROCESSED_RETRIES; attempt++) {
 *   BatchGetItemResponse resp = client.batchGetItem(b -> b.requestItems(request)).join();
 *   found.putAll(mapAttributeMapsToItems(resp.responses()));
 *   Map<String, KeysAndAttributes> unprocessed = resp.unprocessedKeys();
 *   if (unprocessed == null || unprocessed.isEmpty()) {
 *     break;
 *   }
 *   if (attempt == MAX_UNPROCESSED_RETRIES) {
 *     break;
 *   }
 *   delayAsync(unprocessedKeysDelay(attempt)).join();
 *   request = unprocessed;
 * }
 * }</pre>
 *
 * <p>Repository implementations wire this pattern for reservation batch reads together with
 * {@link ReservationBatchGetItemHelper} (keys and item merge). Client retry knobs for unrelated
 * failures are described on {@link DynamoDbConfig}.
 */
public final class BatchGetItemHelper {

    /**
     * Maximum number of {@code BatchGetItem} retries after the first response when unprocessed keys
     * are still present. Attempt {@code 0} is the first service call. Retries use attempt values
     * {@code 1} through {@code MAX_UNPROCESSED_RETRIES} inclusive.
     */
    public static final int MAX_UNPROCESSED_RETRIES = 8;

    /**
     * Backoff strategy used to compute delays between retries for unprocessed keys from
     * {@code BatchGetItem}.
     *
     * <p>Delegates to {@link BackoffStrategy#exponentialDelay(Duration, Duration)} with full jitter
     * (50 ms base and 1 s cap) so spacing matches the style used by SDK client retries elsewhere.
     *
     * @see BackoffStrategy#computeDelay(int)
     */
    public static final BackoffStrategy UNPROCESSED_KEY_BACKOFF =
            BackoffStrategy.exponentialDelay(Duration.ofMillis(50), Duration.ofSeconds(1));

    /**
     * Prevents instantiation of this utility type.
     */
    private BatchGetItemHelper() {
    }

    /**
     * Computes how long to wait before issuing the next {@code BatchGetItem} for unprocessed keys.
     *
     * <p>The SDK {@link BackoffStrategy#computeDelay(int)} method uses a 1-based attempt index where
     * attempt {@code 1} yields zero delay (no wait before the first call). Repository code uses a
     * 0-based loop index for the call that just returned unprocessed keys. The mapping uses
     * {@code loopAttempt + 2} as the SDK attempt so the first retry after attempt {@code 0} picks up
     * a non-zero delay.
     *
     * @param attempt zero-based index of the attempt that produced unprocessed keys
     * @return non-negative duration to wait before the next retry
     */
    public static Duration unprocessedKeysDelay(int attempt) {
        return UNPROCESSED_KEY_BACKOFF.computeDelay(attempt + 2);
    }

    /**
     * Returns distinct strings in first-seen order. Useful before {@code BatchGetItem} so duplicate
     * keys are not sent twice while the response order still follows the original request intent.
     *
     * @param identifiers input list which may contain duplicates
     * @return immutable copy with duplicates removed (order preserved)
     */
    public static List<String> distinctPreserveOrder(List<String> identifiers) {
        return List.copyOf(new LinkedHashSet<>(identifiers));
    }

    /**
     * Executes a {@code BatchGetItem} flow, merging each response into a mutable accumulator and
     * retrying only the {@code UnprocessedKeys} set until it is empty or the retry cap is reached.
     *
     * <p>The {@code batchCallFn} receives a freshly built {@link BatchGetItemRequest} for each round.
     * The {@code responseMerger} should map returned rows into {@code itemsByIdentifier} using a key
     * stable across retries (for example reservation id).
     *
     * @param requestItems initial table key set for the first call
     * @param batchCallFn async service call implementation for one {@code BatchGetItem} round
     * @param responseMerger merges response rows into the shared accumulator map
     * @param logger logger used when retries are exhausted and a partial result is returned
     * @param <T> mapped item type held in the accumulator
     * @return future completing with all mapped items obtained before completion or retry exhaustion
     */
    public static <T> CompletableFuture<Map<String, T>> accumulateWithRetry(
            Map<String, KeysAndAttributes> requestItems,
            Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn,
            BiConsumer<BatchGetItemResponse, Map<String, T>> responseMerger,
            Logger logger) {
        return accumulateWithRetry(requestItems, new HashMap<>(), 0, batchCallFn, responseMerger, logger);
    }

    /**
     * Continues the retry flow for the current key set and shared accumulator.
     *
     * @param requestItems table name to key-set mapping for the current attempt
     * @param itemsByIdentifier mutable accumulator keyed by caller-defined identifier
     * @param attempt zero-based attempt index for the next service call
     * @param batchCallFn async service call implementation for one {@code BatchGetItem} round
     * @param responseMerger merges returned rows into {@code itemsByIdentifier}
     * @param logger logger used when retries are exhausted
     * @param <T> mapped item type held in the accumulator
     * @return future completing with the shared accumulator after this attempt chain finishes
     */
    private static <T> CompletableFuture<Map<String, T>> accumulateWithRetry(
            Map<String, KeysAndAttributes> requestItems,
            Map<String, T> itemsByIdentifier,
            int attempt,
            Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn,
            BiConsumer<BatchGetItemResponse, Map<String, T>> responseMerger,
            Logger logger) {
        CompletableFuture<Map<String, T>> result = batchCallFn.apply(buildBatchGetItemRequest(requestItems))
                .thenCompose(response -> handleBatchGetResponse(
                        response, itemsByIdentifier, attempt, batchCallFn, responseMerger, logger));
        return result;
    }

    /**
     * Builds the low-level request object for one {@code BatchGetItem} round.
     *
     * @param requestItems table name to key-set mapping for the current attempt
     * @return immutable request object passed to the async client
     */
    private static BatchGetItemRequest buildBatchGetItemRequest(Map<String, KeysAndAttributes> requestItems) {
        return BatchGetItemRequest.builder()
                .requestItems(requestItems)
                .build();
    }

    /**
     * Merges one response into the accumulator, then either completes, logs partial success, or
     * schedules the next retry for {@code UnprocessedKeys}.
     *
     * @param response current {@code BatchGetItem} response
     * @param itemsByIdentifier mutable accumulator keyed by caller-defined identifier
     * @param attempt zero-based attempt index for the response that just completed
     * @param batchCallFn async service call implementation for one {@code BatchGetItem} round
     * @param responseMerger merges returned rows into {@code itemsByIdentifier}
     * @param logger logger used when retries are exhausted
     * @param <T> mapped item type held in the accumulator
     * @return future completing with the shared accumulator once processing for this response is done
     */
    private static <T> CompletableFuture<Map<String, T>> handleBatchGetResponse(
            BatchGetItemResponse response,
            Map<String, T> itemsByIdentifier,
            int attempt,
            Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn,
            BiConsumer<BatchGetItemResponse, Map<String, T>> responseMerger,
            Logger logger) {
        responseMerger.accept(response, itemsByIdentifier);

        Map<String, KeysAndAttributes> unprocessed = response.unprocessedKeys();
        if (unprocessed == null || unprocessed.isEmpty()) {
            return completeAccumulator(itemsByIdentifier);
        }
        if (attempt >= MAX_UNPROCESSED_RETRIES) {
            logRetryExhausted(logger, unprocessed, attempt);
            return completeAccumulator(itemsByIdentifier);
        }
        return retryUnprocessedKeys(unprocessed, itemsByIdentifier, attempt, batchCallFn, responseMerger, logger);
    }

    /**
     * Waits for the computed backoff delay, then resubmits only the unprocessed keys.
     *
     * @param unprocessed table name to key-set mapping returned in {@code UnprocessedKeys}
     * @param itemsByIdentifier mutable accumulator keyed by caller-defined identifier
     * @param attempt zero-based attempt index that produced {@code unprocessed}
     * @param batchCallFn async service call implementation for one {@code BatchGetItem} round
     * @param responseMerger merges returned rows into {@code itemsByIdentifier}
     * @param logger logger used when retries are exhausted deeper in the recursion
     * @param <T> mapped item type held in the accumulator
     * @return future completing with the shared accumulator after the retry chain finishes
     */
    private static <T> CompletableFuture<Map<String, T>> retryUnprocessedKeys(
            Map<String, KeysAndAttributes> unprocessed,
            Map<String, T> itemsByIdentifier,
            int attempt,
            Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn,
            BiConsumer<BatchGetItemResponse, Map<String, T>> responseMerger,
            Logger logger) {
        CompletableFuture<Map<String, T>> result = delayAsync(unprocessedKeysDelay(attempt))
                .thenCompose(ignored -> accumulateWithRetry(
                        unprocessed, itemsByIdentifier, attempt + 1, batchCallFn, responseMerger, logger));
        return result;
    }

    /**
     * Logs the partial-success outcome after the retry cap is reached.
     *
     * @param logger logger receiving the warning
     * @param unprocessed key sets that still could not be processed
     * @param attempt zero-based retry index at which processing stopped
     */
    private static void logRetryExhausted(Logger logger,
                                          Map<String, KeysAndAttributes> unprocessed,
                                          int attempt) {
        logger.warn("BatchGetItem returning partial result after retry exhaustion: unprocessedKeyCount={}, attemptCount={}",
                countUnprocessedKeys(unprocessed),
                attempt);
    }

    /**
     * Counts the total number of keys remaining across every table entry in
     * {@code UnprocessedKeys}.
     *
     * @param unprocessed table name to key-set mapping from the DynamoDB response
     * @return total number of keys still outstanding
     */
    private static int countUnprocessedKeys(Map<String, KeysAndAttributes> unprocessed) {
        return unprocessed.values().stream()
                .mapToInt(keysAndAttributes -> keysAndAttributes.keys().size())
                .sum();
    }

    /**
     * Wraps the shared accumulator in a completed future to keep the async flow uniform.
     *
     * @param itemsByIdentifier mutable accumulator keyed by caller-defined identifier
     * @param <T> mapped item type held in the accumulator
     * @return already-completed future containing {@code itemsByIdentifier}
     */
    private static <T> CompletableFuture<Map<String, T>> completeAccumulator(Map<String, T> itemsByIdentifier) {
        return CompletableFuture.completedFuture(itemsByIdentifier);
    }

    /**
     * Splits mapped batch-get items into found rows and missing identifiers in the same order as the
     * deduplicated request list.
     *
     * @param distinctIdentifiersInOrder deduplicated request identifiers in first-seen order
     * @param itemsByIdentifier mapped rows keyed by identifier
     * @param resultFactory builds the caller-specific result type from found items and missing ids
     * @param <T> mapped item type
     * @param <R> result type returned to the caller
     * @return result produced by {@code resultFactory}
     */
    public static <T, R> R toOrderedBatchGetResult(
            List<String> distinctIdentifiersInOrder,
            Map<String, T> itemsByIdentifier,
            BiFunction<List<T>, List<String>, R> resultFactory) {
        List<T> foundItems = new ArrayList<>();
        List<String> missingIdentifiers = new ArrayList<>();
        for (String identifier : distinctIdentifiersInOrder) {
            T item = itemsByIdentifier.get(identifier);
            if (item != null) {
                foundItems.add(item);
            } else {
                missingIdentifiers.add(identifier);
            }
        }
        return resultFactory.apply(List.copyOf(foundItems), List.copyOf(missingIdentifiers));
    }

    /**
     * Completes asynchronously after the given delay without blocking a request thread (uses the
     * default async executor from {@link CompletableFuture}).
     *
     * @param delay wall-clock time to wait before completion
     * @return future that completes normally after the delay (or immediately if the delay is zero)
     */
    public static CompletableFuture<Void> delayAsync(Duration delay) {
        long millis = delay.toMillis();
        if (millis <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }
}
