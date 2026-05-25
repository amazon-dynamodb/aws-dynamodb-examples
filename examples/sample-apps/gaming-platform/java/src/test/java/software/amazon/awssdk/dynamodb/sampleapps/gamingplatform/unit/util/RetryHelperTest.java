package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.RetryHelper;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetryHelper}.
 *
 * <p>Uses a mocked {@link DynamoDbAsyncClient} to verify batch-get retry loops, unprocessed key
 * handling, and exhaustion behaviour.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class RetryHelperTest {

    private static final String TABLE = "TestTable";

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void shouldCollectAllItemsWhenNoUnprocessedKeys() {
        Map<String, AttributeValue> item1 = Map.of("PK", AttributeValue.fromS("USER#1"));
        Map<String, AttributeValue> item2 = Map.of("PK", AttributeValue.fromS("USER#2"));

        BatchGetItemResponse response = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of(item1, item2)))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        BatchGetItemRequest request = BatchGetItemRequest.builder()
                .requestItems(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(List.of(
                                Map.of("PK", AttributeValue.fromS("USER#1")),
                                Map.of("PK", AttributeValue.fromS("USER#2"))))
                        .build()))
                .build();

        List<Map<String, AttributeValue>> result =
                RetryHelper.executeBatchGetUntilComplete(client, request, 3, 1).join();

        assertThat(result).hasSize(2);
        verify(client, times(1)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void shouldRetryWhenUnprocessedKeysExist() {
        Map<String, AttributeValue> item1 = Map.of("PK", AttributeValue.fromS("USER#1"));
        Map<String, AttributeValue> item2 = Map.of("PK", AttributeValue.fromS("USER#2"));
        Map<String, AttributeValue> unprocessedKey = Map.of("PK", AttributeValue.fromS("USER#2"));

        BatchGetItemResponse firstResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of(item1)))
                .unprocessedKeys(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(List.of(unprocessedKey))
                        .build()))
                .build();

        BatchGetItemResponse secondResponse = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of(item2)))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(firstResponse))
                .thenReturn(CompletableFuture.completedFuture(secondResponse));

        BatchGetItemRequest request = BatchGetItemRequest.builder()
                .requestItems(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(List.of(
                                Map.of("PK", AttributeValue.fromS("USER#1")),
                                Map.of("PK", AttributeValue.fromS("USER#2"))))
                        .build()))
                .build();

        List<Map<String, AttributeValue>> result =
                RetryHelper.executeBatchGetUntilComplete(client, request, 3, 1).join();

        assertThat(result).hasSize(2);
        verify(client, times(2)).batchGetItem(any(BatchGetItemRequest.class));
    }

    @Test
    void shouldThrowWhenMaxRoundsExceeded() {
        Map<String, AttributeValue> unprocessedKey = Map.of("PK", AttributeValue.fromS("USER#1"));

        BatchGetItemResponse responseWithUnprocessed = BatchGetItemResponse.builder()
                .responses(Map.of(TABLE, List.of()))
                .unprocessedKeys(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(List.of(unprocessedKey))
                        .build()))
                .build();

        when(client.batchGetItem(any(BatchGetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(responseWithUnprocessed));

        BatchGetItemRequest request = BatchGetItemRequest.builder()
                .requestItems(Map.of(TABLE, KeysAndAttributes.builder()
                        .keys(List.of(unprocessedKey))
                        .build()))
                .build();

        assertThatThrownBy(() ->
                RetryHelper.executeBatchGetUntilComplete(client, request, 1, 1).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                .hasMessageContaining("unprocessed keys");
    }
}
