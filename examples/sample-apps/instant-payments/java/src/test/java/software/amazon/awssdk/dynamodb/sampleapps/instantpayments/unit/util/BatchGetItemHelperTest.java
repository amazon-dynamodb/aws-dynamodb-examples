package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.BatchGetItemHelper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

/**
 * Unit tests for {@link BatchGetItemHelper}.
 *
 * <p>Covers SDK attempt offset applied to backoff, retry-accumulate flow over unprocessed keys,
 * retry-cap exhaustion, and ordering helpers.
 */
@Tag("unit")
class BatchGetItemHelperTest {

    private static final String TABLE = "JavaInstantPayments";

    @Test
    void unprocessedKeysDelay_whenFirstRetry_shouldNotUseZeroDelayFirstAttempt() {
        boolean anyPositive = false;
        for (int i = 0; i < 200 && !anyPositive; i++) {
            if (BatchGetItemHelper.unprocessedKeysDelay(0).toNanos() > 0) {
                anyPositive = true;
            }
        }
        assertThat(anyPositive)
                .as("offset must shift loop attempt 0 past the zero-delay SDK attempt 1")
                .isTrue();
    }

    @Test
    void unprocessedKeysDelay_whenAcrossRetryRange_shouldStayWithinConfiguredCap() {
        for (int attempt = 0; attempt <= BatchGetItemHelper.MAX_UNPROCESSED_RETRIES; attempt++) {
            Duration delay = BatchGetItemHelper.unprocessedKeysDelay(attempt);
            assertThat(delay).isGreaterThanOrEqualTo(Duration.ZERO);
            assertThat(delay).isLessThanOrEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void delayAsync_whenZeroOrNegative_shouldCompleteImmediately() {
        assertThat(BatchGetItemHelper.delayAsync(Duration.ZERO).isDone()).isTrue();
        assertThat(BatchGetItemHelper.delayAsync(Duration.ofMillis(-5)).isDone()).isTrue();
    }

    @Test
    void accumulateWithRetry_whenNoUnprocessedKeys_shouldMergeOnceAndComplete() {
        AtomicInteger calls = new AtomicInteger();
        Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn = req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(response(Map.of("r1", "A"), false));
        };

        Map<String, String> result = BatchGetItemHelper.accumulateWithRetry(
                initialRequest(), batchCallFn, merger(), LoggerFactory.getLogger(getClass())).join();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result).containsExactly(Map.entry("r1", "A"));
    }

    @Test
    void accumulateWithRetry_whenUnprocessedThenClean_shouldRetryAndMergeAll() {
        AtomicInteger calls = new AtomicInteger();
        Function<BatchGetItemRequest, CompletableFuture<BatchGetItemResponse>> batchCallFn = req -> {
            int call = calls.incrementAndGet();
            // First call leaves keys unprocessed. Second call drains them.
            return CompletableFuture.completedFuture(
                    call == 1 ? response(Map.of("r1", "A"), true) : response(Map.of("r2", "B"), false));
        };

        Map<String, String> result = BatchGetItemHelper.accumulateWithRetry(
                initialRequest(), batchCallFn, merger(), LoggerFactory.getLogger(getClass())).join();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result).containsOnly(Map.entry("r1", "A"), Map.entry("r2", "B"));
    }

    @Test
    void distinctPreserveOrder_whenDuplicates_shouldKeepFirstSeenOrder() {
        assertThat(BatchGetItemHelper.distinctPreserveOrder(List.of("b", "a", "b", "c", "a")))
                .containsExactly("b", "a", "c");
    }

    @Test
    void toOrderedBatchGetResult_whenSomeMissing_shouldSplitFoundAndMissingInRequestOrder() {
        List<String> requested = List.of("r1", "r2", "r3");
        Map<String, String> found = Map.of("r1", "A", "r3", "C");

        List<List<String>> split = BatchGetItemHelper.toOrderedBatchGetResult(
                requested, found, (foundItems, missing) -> List.of(foundItems, missing));

        assertThat(split.get(0)).containsExactly("A", "C");
        assertThat(split.get(1)).containsExactly("r2");
    }

    /**
     * Builds the initial request with a single key for the test table.
     */
    private static Map<String, KeysAndAttributes> initialRequest() {
        return Map.of(TABLE, KeysAndAttributes.builder()
                .keys(List.of(Map.of("id", AttributeValue.builder().s("r1").build())))
                .build());
    }

    /**
     * Builds a response with mapped rows, optionally flagging keys as unprocessed.
     *
     * @param rows identifier and value pairs to include in response
     * @param withUnprocessed whether to mark keys as unprocessed
     * @return configured batch response
     */
    private static BatchGetItemResponse response(Map<String, String> rows, boolean withUnprocessed) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        rows.forEach((id, val) -> items.add(Map.of(
                "id", AttributeValue.builder().s(id).build(),
                "val", AttributeValue.builder().s(val).build())));

        BatchGetItemResponse.Builder builder = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, items));
        if (withUnprocessed) {
            builder.unprocessedKeys(Map.of(TABLE, KeysAndAttributes.builder()
                    .keys(List.of(Map.of("id", AttributeValue.builder().s("r1").build())))
                    .build()));
        }
        return builder.build();
    }

    /**
     * Merges id-to-value rows from response into the shared accumulator.
     *
     * @return bifunction that processes response items into the accumulator
     */
    private static BiConsumer<BatchGetItemResponse, Map<String, String>> merger() {
        return (response, accumulator) -> response.responses()
                .getOrDefault(TABLE, List.of())
                .forEach(item -> accumulator.put(item.get("id").s(), item.get("val").s()));
    }
}
