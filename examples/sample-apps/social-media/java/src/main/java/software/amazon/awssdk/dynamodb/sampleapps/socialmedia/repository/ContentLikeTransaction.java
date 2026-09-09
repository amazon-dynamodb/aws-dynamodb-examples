package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.Like;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.PostMeta;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Builds the at-most-once like {@code TransactWriteItems} request, shared by both
 * {@link ContentRepository} implementations so the observable write shape is identical.
 *
 * <p>The transaction writes items in {@link LikeTransactItemOrder}: a conditional {@code Put} of
 * the {@code LIKE#} edge ({@code attribute_not_exists(SK)}) at {@link LikeTransactItemOrder#LIKE_PUT},
 * then an {@code Update} on the {@code POST_META} row with {@code ADD likeCount :one} guarded by
 * {@code attribute_exists(PK)} at {@link LikeTransactItemOrder#COUNTER_UPDATE}. A conditional
 * failure cancels the whole transaction so the counter never increments without the edge, mapping
 * to {@code ALREADY_LIKED} (duplicate) or {@code POST_NOT_FOUND} (missing post).
 */
public final class ContentLikeTransaction {

    private static final TableSchema<Like> LIKE_SCHEMA = TableSchema.fromBean(Like.class);

    /** Utility class, not instantiated. */
    private ContentLikeTransaction() {
    }

    /**
     * Builds the like transaction request against the given Content table.
     *
     * @param tableName Content table name
     * @param like      the like edge to write
     * @return the transactional write request
     */
    public static TransactWriteItemsRequest build(String tableName, Like like) {
        Map<String, AttributeValue> postKey = RepositoryKeys.pkSk(
                PostMeta.partitionKey(like.getPostId()), PostMeta.SORT_KEY);
        TransactionBuilder builder = new TransactionBuilder();
        builder.put(tableName, LIKE_SCHEMA.itemToMap(like, true))
                .conditionExpression("attribute_not_exists(SK)");
        builder.update(tableName, postKey, "ADD likeCount :one", Map.of(":one", AttributeValue.fromN("1")))
                .conditionExpression("attribute_exists(PK)");
        return builder.build();
    }

    /**
     * Writes the like edge and increments the post counter through the shared transaction.
     *
     * @param client    low-level async client
     * @param tableName Content table name
     * @param like      the like edge to write
     * @return completion when the transaction succeeds
     */
    static CompletableFuture<Void> put(DynamoDbAsyncClient client, String tableName, Like like) {
        return client.transactWriteItems(build(tableName, like)).thenApply(ignored -> null);
    }
}
