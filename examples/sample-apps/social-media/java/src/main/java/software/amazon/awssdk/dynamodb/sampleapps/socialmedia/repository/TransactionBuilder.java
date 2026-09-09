package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * Accumulates fixed-size {@code TransactWriteItems} {@code Put} and {@code Update} entries.
 *
 * <p>Each {@link #put} or {@link #update} starts one transaction item. {@link #conditionExpression}
 * attaches to that current item. {@link #build} returns the request. This builder is for
 * single-threaded request construction and is not safe for concurrent mutation.
 */
final class TransactionBuilder {

    private final List<TransactWriteItem> items = new ArrayList<>();

    private Put.Builder pendingPut;

    private Update.Builder pendingUpdate;

    /**
     * Creates an empty write transaction.
     */
    TransactionBuilder() {
    }

    /**
     * Starts a {@code Put} of the given item on {@code tableName}.
     *
     * @param tableName DynamoDB table that receives the item
     * @param item      attribute map to write
     * @return this builder
     */
    TransactionBuilder put(String tableName, Map<String, AttributeValue> item) {
        completePending();
        pendingPut = Put.builder().tableName(tableName).item(item);
        return this;
    }

    /**
     * Starts an {@code Update} of the given key on {@code tableName}.
     *
     * @param tableName                  DynamoDB table that receives the update
     * @param key                        {@code PK}/{@code SK} key map
     * @param updateExpression           update expression, for example {@code ADD likeCount :one}
     * @param expressionAttributeValues  values referenced by {@code updateExpression}
     * @return this builder
     */
    TransactionBuilder update(String tableName, Map<String, AttributeValue> key, String updateExpression,
                              Map<String, AttributeValue> expressionAttributeValues) {
        completePending();
        pendingUpdate = Update.builder()
                .tableName(tableName)
                .key(key)
                .updateExpression(updateExpression)
                .expressionAttributeValues(expressionAttributeValues);
        return this;
    }

    /**
     * Sets the condition on the current {@code Put} or {@code Update}.
     *
     * @param conditionExpression DynamoDB condition, typically {@code attribute_not_exists} or
     *                            {@code attribute_exists}
     * @return this builder
     * @throws IllegalStateException if no {@link #put} or {@link #update} is pending
     */
    TransactionBuilder conditionExpression(String conditionExpression) {
        if (pendingPut != null) {
            pendingPut.conditionExpression(conditionExpression);
            return this;
        }
        if (pendingUpdate != null) {
            pendingUpdate.conditionExpression(conditionExpression);
            return this;
        }
        throw new IllegalStateException("conditionExpression requires put or update first");
    }

    /**
     * Completes the current item and returns the accumulated {@code TransactWriteItems} request.
     *
     * @return request containing every accumulated write item in insertion order
     */
    TransactWriteItemsRequest build() {
        completePending();
        List<TransactWriteItem> transactItems = List.copyOf(items);
        return TransactWriteItemsRequest.builder().transactItems(transactItems).build();
    }

    private void completePending() {
        if (pendingPut != null) {
            items.add(TransactWriteItem.builder().put(pendingPut.build()).build());
            pendingPut = null;
            return;
        }
        if (pendingUpdate != null) {
            items.add(TransactWriteItem.builder().update(pendingUpdate.build()).build());
            pendingUpdate = null;
        }
    }
}
