package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.MerchantGsiProjectionAttributes;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

/**
 * Contract test: the test table's merchant GSIs must match production projection choices so integration
 * tests exercise the same index materialization as {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbTableInitializer}.
 */
@Tag("integration")
public class MerchantGsiProjectionSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    /**
     * {@code GSI_MERCHANT_PAYMENTS} uses full item projection so merchant-wide lists need no base-table fetch.
     */
    @Test
    void gsi_merchantPayments_shouldProjectAllAttributes() {
        var table = dynamoDbAsyncClient
                .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
                .join()
                .table();

        var projection = table.globalSecondaryIndexes().stream()
                .filter(idx -> PaymentStreamHead.GSI_MERCHANT_PAYMENTS.equals(idx.indexName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing GSI " + PaymentStreamHead.GSI_MERCHANT_PAYMENTS))
                .projection();

        assertThat(projection.projectionType()).isEqualTo(ProjectionType.ALL);
    }

    /**
     * {@code GSI_MERCHANT_STATE_PAYMENTS} must match the INCLUDE list shared with production table setup.
     */
    @Test
    void gsi_merchantStatePayments_shouldUseIncludeWithMerchantListNonKeys() {
        var table = dynamoDbAsyncClient
                .describeTable(DescribeTableRequest.builder().tableName(tableName).build())
                .join()
                .table();

        var projection = table.globalSecondaryIndexes().stream()
                .filter(idx -> PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS.equals(idx.indexName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing GSI " + PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS))
                .projection();

        assertThat(projection.projectionType()).isEqualTo(ProjectionType.INCLUDE);
        Set<String> expected = MerchantGsiProjectionAttributes.GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS.stream()
                .collect(Collectors.toUnmodifiableSet());
        assertThat(projection.nonKeyAttributes()).containsExactlyInAnyOrderElementsOf(expected);
    }
}
