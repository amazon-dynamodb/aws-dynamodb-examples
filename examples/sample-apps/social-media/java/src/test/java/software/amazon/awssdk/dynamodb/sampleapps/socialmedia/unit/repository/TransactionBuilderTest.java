package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * Unit coverage for the shared {@code TransactWriteItems} write DSL.
 *
 * <p>Two puts and a put-plus-update must keep each item's table, key or payload, and condition
 * expression. No DynamoDB service is required.
 */
@Tag("unit")
class TransactionBuilderTest {

    private static final String TABLE_NAME = "JavaContent";

    @Test
    void build_whenTwoPuts_returnsBothConditionalPuts() {
        Map<String, AttributeValue> firstItem = Map.of(
                "PK", AttributeValue.fromS("POST#post_1"),
                "SK", AttributeValue.fromS("META"));
        Map<String, AttributeValue> secondItem = Map.of(
                "PK", AttributeValue.fromS("USER#user_1"),
                "SK", AttributeValue.fromS("POST#post_1"));

        TransactWriteItemsRequest request = new TransactionBuilder()
                .put(TABLE_NAME, firstItem)
                .conditionExpression("attribute_not_exists(PK)")
                .put(TABLE_NAME, secondItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        assertThat(request.transactItems()).hasSize(2);
        TransactWriteItem first = request.transactItems().get(0);
        assertThat(first.put().tableName()).isEqualTo(TABLE_NAME);
        assertThat(first.put().item()).isEqualTo(firstItem);
        assertThat(first.put().conditionExpression()).isEqualTo("attribute_not_exists(PK)");
        TransactWriteItem second = request.transactItems().get(1);
        assertThat(second.put().tableName()).isEqualTo(TABLE_NAME);
        assertThat(second.put().item()).isEqualTo(secondItem);
        assertThat(second.put().conditionExpression()).isEqualTo("attribute_not_exists(PK)");
    }

    @Test
    void build_whenPutPlusUpdate_returnsConditionalPutAndUpdate() {
        Map<String, AttributeValue> likeItem = Map.of(
                "PK", AttributeValue.fromS("POST#post_1"),
                "SK", AttributeValue.fromS("LIKE#user_1"));
        Map<String, AttributeValue> postKey = Map.of(
                "PK", AttributeValue.fromS("POST#post_1"),
                "SK", AttributeValue.fromS("META"));
        Map<String, AttributeValue> increment = Map.of(":one", AttributeValue.fromN("1"));

        TransactWriteItemsRequest request = new TransactionBuilder()
                .put(TABLE_NAME, likeItem)
                .conditionExpression("attribute_not_exists(SK)")
                .update(TABLE_NAME, postKey, "ADD likeCount :one", increment)
                .conditionExpression("attribute_exists(PK)")
                .build();

        assertThat(request.transactItems()).hasSize(2);
        TransactWriteItem put = request.transactItems().get(0);
        assertThat(put.put().tableName()).isEqualTo(TABLE_NAME);
        assertThat(put.put().item()).isEqualTo(likeItem);
        assertThat(put.put().conditionExpression()).isEqualTo("attribute_not_exists(SK)");
        TransactWriteItem update = request.transactItems().get(1);
        assertThat(update.update().tableName()).isEqualTo(TABLE_NAME);
        assertThat(update.update().key()).isEqualTo(postKey);
        assertThat(update.update().updateExpression()).isEqualTo("ADD likeCount :one");
        assertThat(update.update().conditionExpression()).isEqualTo("attribute_exists(PK)");
        assertThat(update.update().expressionAttributeValues()).isEqualTo(increment);
    }
}
