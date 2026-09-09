package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbTableInitializer;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.SeedAccountsData;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentStreamHead;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.MerchantGsiProjectionAttributes;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Drops the configured DynamoDB table, recreates it (matching production layout), and seeds accounts
 * from {@link SeedAccountsData}.
 *
 * <p>Used by integration tests for isolation. Table definition must stay aligned with
 * {@link DynamoDbTableInitializer}.
 *
 * <p>Active only for Spring profile {@code test}.
 */
@Component
@Profile("test")
public class DynamoDbTableTestReset {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableTestReset.class);

    private final DynamoDbAsyncClient dynamoDbAsyncClient;

    private final String tableName;

    /**
     * Creates the reset helper bound to the configured table name.
     *
     * @param dynamoDbAsyncClient client for table operations
     * @param tableName payments table name from configuration
     */
    public DynamoDbTableTestReset(DynamoDbAsyncClient dynamoDbAsyncClient,
                                  @Value("${dynamodb.table-name}") String tableName) {
        this.dynamoDbAsyncClient = dynamoDbAsyncClient;
        this.tableName = tableName;
    }

    /**
     * Deletes the table when present, recreates it with production layout, and seeds account rows.
     */
    public void deleteRecreateAndSeed() {
        deleteTableIfExists();
        createTable();
        seedAccounts();
    }

    /** Deletes the configured table and waits until it no longer exists. */
    private void deleteTableIfExists() {
        try {
            dynamoDbAsyncClient.deleteTable(DeleteTableRequest.builder().tableName(tableName).build()).join();
            dynamoDbAsyncClient.waiter().waitUntilTableNotExists(r -> r.tableName(tableName));
            logger.debug("Deleted DynamoDB table '{}'", tableName);
        } catch (CompletionException e) {
            if (e.getCause() instanceof ResourceNotFoundException) {
                logger.debug("DynamoDB table '{}' did not exist, skipping delete", tableName);
                return;
            }
            throw new IllegalStateException("Failed to delete DynamoDB table: " + tableName, e);
        }
    }

    /** Creates the payments table with GSIs, streams, and TTL matching application startup. */
    private void createTable() {
        Projection allProjection = Projection.builder().projectionType(ProjectionType.ALL).build();

        GlobalSecondaryIndex merchantPayments = GlobalSecondaryIndex.builder()
                .indexName(PaymentStreamHead.GSI_MERCHANT_PAYMENTS)
                .keySchema(
                        KeySchemaElement.builder().attributeName("merchantId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("createdAtUtc").keyType(KeyType.RANGE).build(),
                        KeySchemaElement.builder().attributeName("paymentId").keyType(KeyType.RANGE).build())
                .projection(allProjection)
                .build();

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

        CreateTableRequest request = CreateTableRequest.builder()
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
                .globalSecondaryIndexes(merchantPayments, merchantStatePayments)
                .build();

        dynamoDbAsyncClient.createTable(request).join();
        dynamoDbAsyncClient.waiter().waitUntilTableExists(r -> r.tableName(tableName)).join();
        logger.debug("Created DynamoDB table '{}' with Streams (NEW_IMAGE)", tableName);
        try {
            dynamoDbAsyncClient.updateTimeToLive(
                            UpdateTimeToLiveRequest.builder()
                                    .tableName(tableName)
                                    .timeToLiveSpecification(
                                            TimeToLiveSpecification.builder()
                                                    .enabled(true)
                                                    .attributeName("ttl")
                                                    .build())
                                    .build())
                    .join();
            logger.debug("Enabled TTL on attribute ttl for table '{}'", tableName);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Could not enable TTL on table '{}' (may already be enabled or in progress): {}",
                    tableName, cause.getMessage());
        }
    }

    /** Inserts seed account rows from {@link SeedAccountsData}. */
    private void seedAccounts() {
        List<Map<String, Object>> accounts = loadSeedData();
        for (Map<String, Object> account : accounts) {
            Map<String, AttributeValue> item = convertToAttributeValueMap(account);
            dynamoDbAsyncClient.putItem(PutItemRequest.builder()
                            .tableName(tableName)
                            .item(item)
                            .build())
                    .join();
        }
        logger.debug("Seeded {} account rows into '{}'", accounts.size(), tableName);
    }

    /** Loads account seed rows as maps for DynamoDB {@code PutItem} conversion. */
    private List<Map<String, Object>> loadSeedData() {
        return SeedAccountsData.accountRowsAsMaps();
    }

    /**
     * Converts a string-keyed seed map into DynamoDB attribute values.
     *
     * @param source seed row with Java scalar values
     * @return item map ready for {@code PutItemRequest}
     */
    private static Map<String, AttributeValue> convertToAttributeValueMap(Map<String, Object> source) {
        Map<String, AttributeValue> item = new HashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            item.put(entry.getKey(), toAttributeValue(entry.getValue()));
        }
        return item;
    }

    /**
     * Maps a Java scalar seed value to the corresponding {@link AttributeValue}.
     *
     * @param value string, number, boolean, or other value coerced to string
     * @return DynamoDB attribute value for the seed field
     */
    private static AttributeValue toAttributeValue(Object value) {
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
