package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.MerchantGsiProjectionAttributes;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Creates the DynamoDB table and seeds initial account data on application startup.
 *
 * <p>Table creation and seeding run on every startup (local and AWS). If the table already
 * exists, creation is skipped and a log message is emitted. Seeding is idempotent.
 *
 * <p>The table uses a single-table design with composite keys:
 * <ul>
 *   <li>{@code PK}: partition key (String)</li>
 *   <li>{@code SK}: sort key (String)</li>
 * </ul>
 *
 * <p>New tables are created with DynamoDB Streams enabled ({@link StreamViewType#NEW_IMAGE})
 * so the payment processor can react to {@code INSERT} of the first payment domain event.
 *
 * <p>Account seed rows come from {@link SeedAccountsData}.
 *
 * <p>DynamoDB TTL is enabled on attribute {@code ttl} for items that set it (e.g. idempotency rows).
 */
@Configuration
public class DynamoDbTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    /** Single-table name from {@code dynamodb.table-name}. */
    @Value("${dynamodb.table-name}")
    private String tableName;

    /**
     * Runs once per application start: ensures table (+ streams) exist, then idempotent account seed.
     *
     * @param dynamoDbAsyncClient low-level client for createTable and PutItem
     * @return runner registered by Spring Boot
     */
    @Bean
    public CommandLineRunner initializeDynamoDbTable(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> {
            createTableIfNotExists(dynamoDbAsyncClient);
            enableTimeToLiveOnTtlAttribute(dynamoDbAsyncClient);
            seedAccountData(dynamoDbAsyncClient);
        };
    }

    /**
     * Creates the DynamoDB table if it does not exist. Logs and continues if the table
     * already exists (e.g. on restarts or when connected to AWS).
     *
     * <p>Streams use {@link StreamViewType#NEW_IMAGE} so {@code INSERT} records include the full item
     * (the poller can detect new OUTBOUND_PAYMENT_CREATED events without an extra read).
     */
    private void createTableIfNotExists(DynamoDbAsyncClient client) {
        CreateTableRequest.Builder requestBuilder = CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("merchantId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("createdAtUtc").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("paymentId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("aggregateState").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .streamSpecification(StreamSpecification.builder()
                        .streamEnabled(true)
                        .streamViewType(StreamViewType.NEW_IMAGE)
                        .build())
                .globalSecondaryIndexes(buildMerchantGsis());

        CreateTableRequest request = requestBuilder.build();

        try {
            client.createTable(request).join();
            client.waiter().waitUntilTableExists(r -> r.tableName(tableName)).join();
            logger.info("Created DynamoDB table with streams enabled: tableName={}, streamViewType=NEW_IMAGE", tableName);
        } catch (Exception e) {
            if (e.getCause() instanceof ResourceInUseException) {
                logger.info("DynamoDB table already exists, skipping creation: tableName={}", tableName);
            } else {
                throw new RuntimeException("Failed to create DynamoDB table: " + tableName, e);
            }
        }
    }

    /**
     * Builds the two merchant GSI definitions using DynamoDB multi-attribute keys.
     *
     * <p>{@code GSI_MERCHANT_PAYMENTS} uses {@link ProjectionType#ALL}. Every base-table attribute is replicated
     * into the index. This is the simplest option: queries never need a follow-up table fetch, but every write to
     * a projected item copies the full attribute set into the index, increasing write amplification and storage.
     *
     * <p>{@code GSI_MERCHANT_STATE_PAYMENTS} uses {@link ProjectionType#INCLUDE} with an explicit attribute list
     * that matches what the merchant-list response needs. This reduces per-item write cost and index storage at the
     * expense of flexibility: if the response grows new fields later, the projection must be updated (or the query
     * must fall back to a base-table fetch for the missing attributes).
     *
     * @see <a href="https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/GSI.html#GSI.Projections">
     *      DynamoDB Developer Guide, Global Secondary Index Projections</a>
     */
    private static List<GlobalSecondaryIndex> buildMerchantGsis() {
        Projection allProjection = Projection.builder().projectionType(ProjectionType.ALL).build();

        GlobalSecondaryIndex merchantPayments = GlobalSecondaryIndex.builder()
                .indexName(PaymentStreamHead.GSI_MERCHANT_PAYMENTS)
                .keySchema(
                        KeySchemaElement.builder().attributeName("merchantId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("createdAtUtc").keyType(KeyType.RANGE).build(),
                        KeySchemaElement.builder().attributeName("paymentId").keyType(KeyType.RANGE).build())
                .projection(allProjection)
                .build();

        // Key attributes (always projected): PK, SK, merchantId, aggregateState, createdAtUtc.
        // Non-key attributes below are the additional fields the merchant-list DTO needs.
        Projection includeProjection = Projection.builder()
                .projectionType(ProjectionType.INCLUDE)
                .nonKeyAttributes(MerchantGsiProjectionAttributes.GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS)
                .build();

        GlobalSecondaryIndex merchantStatePayments = GlobalSecondaryIndex.builder()
                .indexName(PaymentStreamHead.GSI_MERCHANT_STATE_PAYMENTS)
                .keySchema(
                        KeySchemaElement.builder().attributeName("merchantId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("aggregateState").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("createdAtUtc").keyType(KeyType.RANGE).build())
                .projection(includeProjection)
                .build();

        return List.of(merchantPayments, merchantStatePayments);
    }

    /**
     * Enables TTL on the {@code ttl} (epoch seconds) attribute. Idempotent for already-enabled tables.
     */
    private void enableTimeToLiveOnTtlAttribute(DynamoDbAsyncClient client) {
        try {
            client.updateTimeToLive(
                    UpdateTimeToLiveRequest.builder()
                            .tableName(tableName)
                            .timeToLiveSpecification(
                                    TimeToLiveSpecification.builder()
                                            .enabled(true)
                                            .attributeName("ttl")
                                            .build())
                            .build())
                    .join();
            logger.info("DynamoDB TTL enabled: tableName={}, ttlAttribute=ttl", tableName);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn(
                    "Could not enable DynamoDB TTL; table may already have TTL enabled or an update may be in progress: "
                            + "tableName={}, reason={}",
                    tableName,
                    cause.getMessage());
        }
    }

    /**
     * Seeds account data from {@link SeedAccountsData}.
     *
     * <p>Each account is written with a conditional check ({@code attribute_not_exists(PK)})
     * so seeding is idempotent. Re-running the application does not overwrite existing accounts.
     */
    private void seedAccountData(DynamoDbAsyncClient client) {
        List<Map<String, Object>> accounts = SeedAccountsData.accountRowsAsMaps();
        if (accounts.isEmpty()) {
            logger.warn("No seed account data configured for startup seeding");
            return;
        }

        for (Map<String, Object> account : accounts) {
            Map<String, AttributeValue> item = convertToAttributeValueMap(account);
            String pk = item.get("PK").s();

            PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build();

            try {
                client.putItem(putRequest).join();
                logger.info("Seeded account: accountKey={}", pk);
            } catch (Exception e) {
                if (e.getCause() instanceof ConditionalCheckFailedException) {
                    logger.debug("Account already exists, skipping seed: accountKey={}", pk);
                } else {
                    logger.error("Failed to seed account: accountKey={}", pk, e);
                }
            }
        }
    }

    /**
     * Converts a JSON-style map to DynamoDB AttributeValue map.
     */
    private Map<String, AttributeValue> convertToAttributeValueMap(Map<String, Object> source) {
        Map<String, AttributeValue> item = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), toAttributeValue(entry.getValue()));
        }
        return item;
    }

    /**
     * Converts a Java object to a DynamoDB AttributeValue.
     */
    private AttributeValue toAttributeValue(Object value) {
        if (value instanceof String s) {
            return AttributeValue.builder().s(s).build();
        } else if (value instanceof Number n) {
            return AttributeValue.builder().n(n.toString()).build();
        } else if (value instanceof Boolean b) {
            return AttributeValue.builder().bool(b).build();
        }
        return AttributeValue.builder().s(String.valueOf(value)).build();
    }
}
