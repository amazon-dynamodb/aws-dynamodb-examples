package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.mapper.FollowMapper;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowerEdge;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowingEdge;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.FollowTransaction;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Unit coverage for the atomic UserGraph follow write used by both repository implementations.
 */
@Tag("unit")
class FollowTransactionTest {

    private static final String TABLE_NAME = "JavaUserGraph";
    private static final String FOLLOWER_ID = "user_alice";
    private static final String FOLLOWEE_ID = "user_bob";
    private static final String CREATED_AT = "2026-08-07T00:00:00Z";

    @Test
    void build_writesConditionalFollowingAndFollowerPuts() {
        FollowMapper mapper = new FollowMapper();
        FollowingEdge following = mapper.toFollowingEdge(FOLLOWER_ID, FOLLOWEE_ID, CREATED_AT);
        FollowerEdge follower = mapper.toFollowerEdge(FOLLOWER_ID, FOLLOWEE_ID, CREATED_AT);

        TransactWriteItemsRequest request = FollowTransaction.build(TABLE_NAME, following, follower);

        assertThat(request.transactItems()).hasSize(2);
        assertThat(request.transactItems().get(0).put().tableName()).isEqualTo(TABLE_NAME);
        assertThat(request.transactItems().get(0).put().conditionExpression()).isEqualTo("attribute_not_exists(SK)");
        assertThat(request.transactItems().get(0).put().item()).containsEntry("PK",
                AttributeValue.fromS(FollowingEdge.PK_PREFIX + FOLLOWER_ID));
        assertThat(request.transactItems().get(0).put().item()).containsEntry("SK",
                AttributeValue.fromS(FollowingEdge.sortKey(FOLLOWEE_ID)));
        assertThat(request.transactItems().get(0).put().item()).containsEntry("entityType",
                AttributeValue.fromS(FollowingEdge.ENTITY_TYPE));
        assertThat(request.transactItems().get(1).put().tableName()).isEqualTo(TABLE_NAME);
        assertThat(request.transactItems().get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(SK)");
        assertThat(request.transactItems().get(1).put().item()).containsEntry("PK",
                AttributeValue.fromS(FollowerEdge.PK_PREFIX + FOLLOWEE_ID));
        assertThat(request.transactItems().get(1).put().item()).containsEntry("SK",
                AttributeValue.fromS(FollowerEdge.sortKey(FOLLOWER_ID)));
        assertThat(request.transactItems().get(1).put().item()).containsEntry("entityType",
                AttributeValue.fromS(FollowerEdge.ENTITY_TYPE));
    }
}
