package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbConfig;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPage;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetResultPagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;

/**
 * Utilities for draining {@code BatchGetItem} {@code UnprocessedKeys} across retries.
 *
 * <p>This is complementary to SDK-level retries on {@link DynamoDbAsyncClient}: the AWS SDK retries
 * whole failed requests (for example throttling as a failing call). It does not re-submit partial
 * {@code UnprocessedKeys} after an HTTP 200 response. Operators configure client retries explicitly
 * in {@link DynamoDbConfig}.
 *
 * <p>Uses exponential backoff with jitter between rounds that resubmit only the unprocessed keys
 * for low-level and enhanced batch-get callers.
 */
public final class RetryHelper {

    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);

    /** Maximum {@code BatchGetItem} rounds before failing when keys stay unprocessed. */
    public static final int BATCH_GET_MAX_ROUNDS = 5;

    /** Base delay in milliseconds for {@code BatchGetItem} exponential backoff. */
    public static final long BATCH_GET_BASE_DELAY_MS = 100;

    /** Not instantiated. */
    private RetryHelper() {
    }

    /**
     * Executes a {@code BatchGetItem} request, retrying until all {@code UnprocessedKeys} are
     * drained or {@code maxRounds} is exceeded.
     *
     * <p>Each retry waits {@code baseDelayMs * 2^round + random(0, baseDelayMs)} milliseconds
     * before re-sending the remaining keys.
     *
     * @param client      the DynamoDB async client
     * @param request     the initial batch-get request
     * @param maxRounds   maximum number of retry rounds for unprocessed keys
     * @param baseDelayMs base delay in milliseconds for exponential backoff
     * @return all items collected across all rounds, flattened into a single list
     * @throws RuntimeException if unprocessed keys remain after {@code maxRounds}
     */
    public static CompletableFuture<List<Map<String, AttributeValue>>> executeBatchGetUntilComplete(
            DynamoDbAsyncClient client, BatchGetItemRequest request, int maxRounds, long baseDelayMs) {

        return executeBatchGetRound(client, request, new ArrayList<>(), 0, maxRounds, baseDelayMs);
    }

    /**
     * Runs an enhanced {@code batchGetItem} for a single table, retrying until
     * {@link BatchGetResultPage#unprocessedKeysForTable} is empty or {@code maxRounds} is exceeded.
     *
     * <p>Each round issues at most one enhanced {@code BatchGetItem} for the supplied keys, then on
     * unprocessed keys backs off with the same schedule as {@link #executeBatchGetUntilComplete}.
     *
     * @param client      the enhanced async client
     * @param table       the mapped table keys resolve against
     * @param keys        keys to fetch this round (typically at most 100 per DynamoDB limits)
     * @param maxRounds   maximum retry rounds for unprocessed keys
     * @param baseDelayMs base delay in milliseconds for exponential backoff
     * @param <T>         mapped item type
     * @return all items collected across rounds for this key set
     * @throws RuntimeException if unprocessed keys remain after {@code maxRounds}
     */
    public static <T> CompletableFuture<List<T>> executeEnhancedBatchGetUntilComplete(
            DynamoDbEnhancedAsyncClient client,
            DynamoDbAsyncTable<T> table,
            List<Key> keys,
            int maxRounds,
            long baseDelayMs) {

        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        return executeEnhancedBatchGetRound(client, table, keys, new ArrayList<>(), 0, maxRounds, baseDelayMs);
    }

    /**
     * Runs one {@code BatchGetItem} round and schedules further rounds while unprocessed keys remain.
     *
     * @param client       DynamoDB client
     * @param request      request for this round
     * @param accumulated  items collected so far
     * @param round        zero-based round index
     * @param maxRounds    inclusive cap on retry rounds
     * @param baseDelayMs  backoff base delay
     * @return future that completes with all items when no unprocessed keys remain
     * @throws RuntimeException if unprocessed keys remain after {@code maxRounds}
     */
    private static CompletableFuture<List<Map<String, AttributeValue>>> executeBatchGetRound(
            DynamoDbAsyncClient client, BatchGetItemRequest request,
            List<Map<String, AttributeValue>> accumulated, int round, int maxRounds, long baseDelayMs) {

        return client.batchGetItem(request).thenCompose(response -> {
            collectItems(response, accumulated);

            if (!hasUnprocessedKeys(response)) {
                return CompletableFuture.completedFuture(accumulated);
            }

            if (round >= maxRounds) {
                throw new RuntimeException(
                        "BatchGetItem still has unprocessed keys after " + maxRounds + " rounds");
            }

            // Exponential backoff with jitter: base * 2^round + uniform random in [0, base).
            long delayMs = baseDelayMs * (1L << round)
                    + ThreadLocalRandom.current().nextLong(baseDelayMs);
            logger.debug("BatchGetItem retry scheduled [round={}, delayMs={}]", round, delayMs);

            // Resubmit only the keys DynamoDB left unprocessed.
            BatchGetItemRequest retryRequest = BatchGetItemRequest.builder()
                    .requestItems(response.unprocessedKeys())
                    .build();

            // Recursive async call: delay, then continue with the remaining keys.
            return delayedFuture(delayMs)
                    .thenCompose(ignored ->
                            executeBatchGetRound(client, retryRequest, accumulated,
                                    round + 1, maxRounds, baseDelayMs));
        });
    }

    /**
     * One enhanced batch-get round: appends {@code resultsForTable}, then either completes or retries
     * unprocessed keys after backoff.
     *
     * @param client       enhanced client
     * @param table        table resource
     * @param pendingKeys  keys for this request
     * @param accumulated  all items collected so far (mutated)
     * @param round        zero-based retry round
     * @param maxRounds    maximum rounds
     * @param baseDelayMs  backoff base
     * @param <T>          item type
     * @return future that completes when this key set is fully drained
     * @throws RuntimeException if unprocessed keys remain after {@code maxRounds}
     */
    private static <T> CompletableFuture<List<T>> executeEnhancedBatchGetRound(
            DynamoDbEnhancedAsyncClient client,
            DynamoDbAsyncTable<T> table,
            List<Key> pendingKeys,
            List<T> accumulated,
            int round,
            int maxRounds,
            long baseDelayMs) {

        BatchGetItemEnhancedRequest request = batchGetRequestForKeys(table, pendingKeys);

        return firstBatchGetResultPage(client.batchGetItem(request)).thenCompose(page -> {
            accumulated.addAll(page.resultsForTable(table));
            List<Key> unprocessed = page.unprocessedKeysForTable(table);
            // SDK returns null when the table has no unprocessed keys in the response.
            if (unprocessed == null || unprocessed.isEmpty()) {
                return CompletableFuture.completedFuture(accumulated);
            }

            if (round >= maxRounds) {
                throw new RuntimeException(
                        "Enhanced BatchGetItem still has unprocessed keys after " + maxRounds + " rounds");
            }

            // Exponential backoff with jitter before retrying the unprocessed key subset.
            long delayMs = baseDelayMs * (1L << round)
                    + ThreadLocalRandom.current().nextLong(baseDelayMs);
            logger.debug("Enhanced BatchGetItem retry scheduled [round={}, delayMs={}]",
                    round, delayMs);

            // Recursive async call: delay, then retry with only the leftover keys.
            return delayedFuture(delayMs)
                    .thenCompose(ignored ->
                            executeEnhancedBatchGetRound(client, table,
                                    new ArrayList<>(unprocessed), accumulated, round + 1,
                                    maxRounds, baseDelayMs));
        });
    }

    /**
     * Builds a single-table {@link ReadBatch} covering the given keys.
     *
     * @param table mapped table
     * @param keys  DynamoDB keys
     * @param <T>   item type
     * @return enhanced batch-get request
     */
    private static <T> BatchGetItemEnhancedRequest batchGetRequestForKeys(
            DynamoDbAsyncTable<T> table,
            List<Key> keys) {

        // rawClass() returns Class<?>, but the table schema guarantees the type matches T.
        @SuppressWarnings("unchecked")
        ReadBatch.Builder<T> batchBuilder = ReadBatch.builder(table.tableSchema().itemType().rawClass())
                .mappedTableResource(table);
        for (Key key : keys) {
            batchBuilder.addGetItem(key);
        }
        return BatchGetItemEnhancedRequest.builder()
                .addReadBatch(batchBuilder.build())
                .build();
    }

    /**
     * Subscribes to the publisher and completes with the first {@link BatchGetResultPage} only.
     *
     * <p>The returned future completes exceptionally with {@link IllegalStateException} if the
     * publisher signals completion without emitting any pages.
     *
     * @param publisher result stream from {@link DynamoDbEnhancedAsyncClient#batchGetItem}
     * @return future for the first page
     */
    private static CompletableFuture<BatchGetResultPage> firstBatchGetResultPage(
            BatchGetResultPagePublisher publisher) {

        // Bridge the reactive publisher to a single CompletableFuture by taking one page.
        CompletableFuture<BatchGetResultPage> result = new CompletableFuture<>();
        publisher.subscribe(new Subscriber<BatchGetResultPage>() {
            /** Upstream subscription handle used to request pages and cancel the stream. */
            private Subscription subscription;

            /** @param s upstream subscription */
            @Override
            public void onSubscribe(Subscription s) {
                this.subscription = s;
                // Request exactly one page; the enhanced client emits one page per BatchGetItem call.
                s.request(1);
            }

            /** @param page single response page for one {@code BatchGetItem} call */
            @Override
            public void onNext(BatchGetResultPage page) {
                result.complete(page);
                // Only the first page is needed; cancel to avoid unnecessary publisher work.
                subscription.cancel();
            }

            /** @param t failure from the publisher */
            @Override
            public void onError(Throwable t) {
                result.completeExceptionally(t);
            }

            /** Signals a missing page if the stream ends without data. */
            @Override
            public void onComplete() {
                // Guard: onComplete may fire after cancel; only fail if no page was received.
                if (!result.isDone()) {
                    result.completeExceptionally(
                            new IllegalStateException("BatchGetItem publisher completed without a page"));
                }
            }
        });
        return result;
    }

    /**
     * Appends item maps from a batch-get response into {@code accumulated}.
     *
     * @param response    DynamoDB response for one round
     * @param accumulated target list
     */
    private static void collectItems(BatchGetItemResponse response,
                                     List<Map<String, AttributeValue>> accumulated) {
        response.responses().values()
                .forEach(accumulated::addAll);
    }

    /**
     * Checks whether the response contains any tables with genuinely unprocessed keys.
     *
     * @param response batch-get response
     * @return {@code true} when at least one table still has unprocessed keys with non-empty key list
     */
    private static boolean hasUnprocessedKeys(BatchGetItemResponse response) {
        // Three-level guard: the SDK may report hasUnprocessedKeys() = true even when the
        // map or individual key lists are empty, so each level must be verified.
        return response.hasUnprocessedKeys()
                && !response.unprocessedKeys().isEmpty()
                && response.unprocessedKeys().values().stream()
                        .anyMatch(ka -> ka.hasKeys() && !ka.keys().isEmpty());
    }

    /**
     * Completes after {@code delayMs} on the default delayed executor.
     *
     * @param delayMs wait time in milliseconds
     * @return future that completes with {@code null} body after the delay
     */
    private static CompletableFuture<Void> delayedFuture(long delayMs) {
        return CompletableFuture.runAsync(() -> { },
                CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS));
    }
}
