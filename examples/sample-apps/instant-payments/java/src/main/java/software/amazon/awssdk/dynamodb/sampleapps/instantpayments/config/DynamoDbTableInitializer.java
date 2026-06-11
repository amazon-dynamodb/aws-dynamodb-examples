package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Creates the DynamoDB table and seeds demo accounts on startup, or verifies the table when
 * resource creation is disabled.
 *
 * <p>Property {@code dynamodb.create-resources} defaults to {@code false} when unset.
 * <ul>
 *   <li>{@code true}: create the table, enable TTL on {@code ttl}, seed accounts from
 *       {@link SeedAccountsData}</li>
 *   <li>{@code false} or unset: call {@code DescribeTable} and fail fast if the table is missing
 *       or not {@link TableStatus#ACTIVE}</li>
 * </ul>
 *
 * <p>In production, leave the property unset or set {@code false}. Manage schema and seed data in IaC.
 *
 * <p>Single-table keys are string attributes {@code PK} for partition and {@code SK} for sort.
 *
 * <p>New tables enable DynamoDB Streams with {@link StreamViewType#NEW_IMAGE} so the payment processor
 * can react to the first {@code OUTBOUND_PAYMENT_CREATED} event.
 *
 * <p>TTL is enabled on {@code ttl} for items that set it, such as idempotency records.
 *
 * @apiNote Repository and client futures are completed with blocking {@code .join()} on the Spring
 * startup thread. That is intentional bootstrap work, not the HTTP request path, and runs once per
 * process start.
 */
@Configuration
public class DynamoDbTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    /** Single-table name from {@code dynamodb.table-name}. */
    @Value("${dynamodb.table-name}")
    private String tableName;

    /**
     * Runs at startup when {@code dynamodb.create-resources=true}. Creates the table with streams,
     * enables TTL, and idempotently seeds accounts.
     *
     * @param dynamoDbAsyncClient low-level client for createTable and PutItem
     * @return runner registered by Spring Boot
     */
    @Bean
    @ConditionalOnProperty(name = "dynamodb.create-resources", havingValue = "true")
    public CommandLineRunner initializeDynamoDbTable(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> {
            createTableIfNotExists(dynamoDbAsyncClient);
            enableTimeToLiveOnTtlAttribute(dynamoDbAsyncClient);
            seedAccountData(dynamoDbAsyncClient);
        };
    }

    /**
     * Runs at startup when {@code dynamodb.create-resources=false} or unset. Confirms the table
     * exists and is {@link TableStatus#ACTIVE}.
     *
     * @param dynamoDbAsyncClient low-level client for describeTable
     * @return runner registered by Spring Boot
     */
    @Bean
    @ConditionalOnProperty(name = "dynamodb.create-resources", havingValue = "false", matchIfMissing = true)
    public CommandLineRunner verifyDynamoDbTableExists(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> verifyTableExists(dynamoDbAsyncClient);
    }

    /**
     * Calls {@code DescribeTable} and fails fast when the table is missing or not active.
     *
     * @param client low-level DynamoDB client
     * @throws IllegalStateException when the table does not exist or is not {@link TableStatus#ACTIVE}
     */
    void verifyTableExists(DynamoDbAsyncClient client) {
        try {
            DescribeTableResponse response = client.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build()).join();
            TableDescription table = response.table();
            TableStatus status = table.tableStatus();
            if (status != TableStatus.ACTIVE) {
                throw new IllegalStateException(
                        "DynamoDB table is not ACTIVE: tableName=" + tableName + ", status=" + status
                                + ". Provision the table via IaC or set dynamodb.create-resources=true for local dev.");
            }
            logger.info("DynamoDB table verified: tableName={}, status={}", tableName, status);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ResourceNotFoundException) {
                throw new IllegalStateException(
                        "DynamoDB table not found: tableName=" + tableName
                                + ". Provision the table via IaC or set dynamodb.create-resources=true for local dev.",
                        cause);
            }
            if (cause instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            throw new IllegalStateException("Failed to verify DynamoDB table: tableName=" + tableName, cause);
        }
    }

    /**
     * Creates the DynamoDB table when it does not exist. Skips creation when the table is already present.
     *
     * <p>Streams use {@link StreamViewType#NEW_IMAGE} so {@code INSERT} records include the full item
     * and the poller can detect {@code OUTBOUND_PAYMENT_CREATED} without an extra read.
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

        // INCLUDE projection lists only fields needed by the merchant payment list response.
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
     * Enables TTL on the {@code ttl} attribute in epoch seconds. Safe to call when TTL is already enabled.
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
                    "Could not enable DynamoDB TTL, table may already have TTL enabled or an update may be in progress: "
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
