package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.mapper.LikeMapper;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.Like;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.PostMeta;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.ContentLikeTransaction;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.LikeTransactItemOrder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Unit coverage for the atomic Content-table like write used by both repository implementations.
 *
 * <p>Asserts that {@link ContentLikeTransaction} writes the like-edge put and counter update in
 * {@link LikeTransactItemOrder} so cancellation-reason indexes stay aligned with the writer.
 */
@Tag("unit")
class ContentLikeTransactionTest {

    private static final String TABLE_NAME = "JavaContent";
    private static final String POST_ID = "post_1";
    private static final String USER_ID = "user_alice";
    private static final String CREATED_AT = "2026-08-07T00:00:00Z";

    @Test
    void build_writesLikePutThenCounterUpdateInSharedOrder() {
        Like like = new LikeMapper().toLike(POST_ID, USER_ID, CREATED_AT);

        TransactWriteItemsRequest request = ContentLikeTransaction.build(TABLE_NAME, like);

        assertThat(request.transactItems()).hasSize(2);
        assertThat(request.transactItems().get(LikeTransactItemOrder.LIKE_PUT.index()).put().tableName())
                .isEqualTo(TABLE_NAME);
        assertThat(request.transactItems().get(LikeTransactItemOrder.LIKE_PUT.index()).put().conditionExpression())
                .isEqualTo("attribute_not_exists(SK)");
        assertThat(request.transactItems().get(LikeTransactItemOrder.LIKE_PUT.index()).put().item())
                .containsEntry("PK", AttributeValue.fromS(Like.PK_PREFIX + POST_ID))
                .containsEntry("SK", AttributeValue.fromS(Like.sortKey(USER_ID)))
                .containsEntry("entityType", AttributeValue.fromS(Like.ENTITY_TYPE));
        assertThat(request.transactItems().get(LikeTransactItemOrder.COUNTER_UPDATE.index()).update().tableName())
                .isEqualTo(TABLE_NAME);
        assertThat(request.transactItems().get(LikeTransactItemOrder.COUNTER_UPDATE.index()).update()
                .conditionExpression()).isEqualTo("attribute_exists(PK)");
        assertThat(request.transactItems().get(LikeTransactItemOrder.COUNTER_UPDATE.index()).update()
                .updateExpression()).isEqualTo("ADD likeCount :one");
        assertThat(request.transactItems().get(LikeTransactItemOrder.COUNTER_UPDATE.index()).update().key())
                .containsEntry("PK", AttributeValue.fromS(PostMeta.partitionKey(POST_ID)))
                .containsEntry("SK", AttributeValue.fromS(PostMeta.SORT_KEY));
    }
}
