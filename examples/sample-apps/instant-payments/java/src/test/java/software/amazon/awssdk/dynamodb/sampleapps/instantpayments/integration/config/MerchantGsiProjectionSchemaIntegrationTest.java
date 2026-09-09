package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbTableInitializer;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.MerchantGsiProjectionAttributes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

/**
 * Contract test: the test table's merchant GSIs must match production projection choices so integration
 * tests exercise the same index materialization as {@link DynamoDbTableInitializer}.
 *
 * <p>It also documents the multi-attribute key schema the sample showcases: both merchant GSIs use a
 * composite key built from several attributes, and these assertions pin that schema so a regression in
 * {@link DynamoDbTableInitializer} would fail the build.
 */
@Tag("integration")
public class MerchantGsiProjectionSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Test
    void verifyMerchantPaymentsGsi_whenTableInitialized_shouldProjectAllAttributes() {
        var table = dynamoDbAsyncClient
                .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
                .join()
                .table();

        var index = table.globalSecondaryIndexes().stream()
                .filter(idx -> PaymentStreamHead.GSI_MERCHANT_PAYMENTS.equals(idx.indexName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing GSI " + PaymentStreamHead.GSI_MERCHANT_PAYMENTS));

        assertThat(index.projection().projectionType()).isEqualTo(ProjectionType.ALL);

        // Multi-attribute key: partition merchantId, sort components createdAtUtc then paymentId.
        assertThat(index.keySchema())
                .extracting(KeySchemaElement::attributeName, KeySchemaElement::keyType)
                .containsExactly(
                        tuple("merchantId", KeyType.HASH),
                        tuple("createdAtUtc", KeyType.RANGE),
                        tuple("paymentId", KeyType.RANGE));
    }

    @Test
    void verifyMerchantStatePaymentsGsi_whenTableInitialized_shouldUseIncludeWithMerchantListNonKeys() {
        var table = dynamoDbAsyncClient
                .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
                .join()
                .table();

        var index = table.globalSecondaryIndexes().stream()
                .filter(idx -> PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS.equals(idx.indexName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing GSI " + PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS));

        var projection = index.projection();
        assertThat(projection.projectionType()).isEqualTo(ProjectionType.INCLUDE);
        Set<String> expected = MerchantGsiProjectionAttributes.GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS.stream()
                .collect(Collectors.toUnmodifiableSet());
        assertThat(projection.nonKeyAttributes()).containsExactlyInAnyOrderElementsOf(expected);

        // Multi-attribute key: composite partition merchantId plus aggregateState, sort createdAtUtc.
        assertThat(index.keySchema())
                .extracting(KeySchemaElement::attributeName, KeySchemaElement::keyType)
                .containsExactly(
                        tuple("merchantId", KeyType.HASH),
                        tuple("aggregateState", KeyType.HASH),
                        tuple("createdAtUtc", KeyType.RANGE));
    }
}
