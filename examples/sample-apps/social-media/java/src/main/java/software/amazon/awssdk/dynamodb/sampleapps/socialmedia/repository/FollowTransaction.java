package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import java.util.concurrent.CompletableFuture;

import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowerEdge;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowingEdge;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Builds the atomic two-edge follow {@code TransactWriteItems} request, shared by both
 * {@link UserGraphRepository} implementations so the observable write shape is identical.
 *
 * <p>The transaction conditionally puts the caller's {@code FOLLOWING#} edge and the target's
 * {@code FOLLOWER#} edge, each guarded by {@code attribute_not_exists(SK)}. A conditional failure
 * cancels the whole transaction so one edge cannot exist without the other, mapping to
 * {@code ALREADY_FOLLOWING}.
 */
public final class FollowTransaction {

    private static final TableSchema<FollowingEdge> FOLLOWING_SCHEMA = TableSchema.fromBean(FollowingEdge.class);
    private static final TableSchema<FollowerEdge> FOLLOWER_SCHEMA = TableSchema.fromBean(FollowerEdge.class);

    /** Utility class, not instantiated. */
    private FollowTransaction() {
    }

    /**
     * Builds the follow transaction request against the given UserGraph table.
     *
     * @param tableName UserGraph table name
     * @param following the caller's following edge
     * @param follower  the target's follower edge
     * @return the transactional write request
     */
    public static TransactWriteItemsRequest build(String tableName, FollowingEdge following,
                                                 FollowerEdge follower) {
        return new TransactionBuilder()
                .put(tableName, FOLLOWING_SCHEMA.itemToMap(following, true))
                .conditionExpression("attribute_not_exists(SK)")
                .put(tableName, FOLLOWER_SCHEMA.itemToMap(follower, true))
                .conditionExpression("attribute_not_exists(SK)")
                .build();
    }

    /**
     * Writes both follow edges through the shared transaction.
     *
     * @param client    low-level async client
     * @param tableName UserGraph table name
     * @param following the caller's following edge
     * @param follower  the target's follower edge
     * @return completion when the transaction succeeds
     */
    static CompletableFuture<Void> put(DynamoDbAsyncClient client, String tableName, FollowingEdge following,
                                       FollowerEdge follower) {
        return client.transactWriteItems(build(tableName, following, follower)).thenApply(ignored -> null);
    }
}
