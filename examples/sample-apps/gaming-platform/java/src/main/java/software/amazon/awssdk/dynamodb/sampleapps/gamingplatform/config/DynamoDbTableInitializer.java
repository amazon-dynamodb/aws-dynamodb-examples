package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Creates DynamoDB tables, enables TTL, and seeds sample data at startup when the
 * {@code dynamodb.create-resources} flag is true.
 *
 * <p>When {@code dynamodb.create-resources} is true (local development default), the
 * {@link #initializeDynamoDbTables(DynamoDbAsyncClient)} runner creates all three tables, enables TTL,
 * and seeds demo data. Table creation is idempotent: {@link ResourceInUseException} is caught and
 * logged when the table already exists. Seed inserts use {@code attribute_not_exists(PK)} to avoid
 * overwriting existing data on restarts. Each table is awaited until ACTIVE after creation, because
 * on live AWS the CreateTable call returns while the table is still CREATING. TTL enablement and
 * seeding require an ACTIVE table.
 *
 * <p>When {@code dynamodb.create-resources} is false or absent (production default), the
 * {@link #verifyDynamoDbTables(DynamoDbAsyncClient)} runner only checks each table exists and fails
 * fast with {@link IllegalStateException} if one is missing. It never creates tables or seeds data,
 * so production credentials need no control-plane permissions. Schema management belongs in
 * infrastructure as code.
 */
@Configuration
public class DynamoDbTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    /** Physical PlayerState table name from configuration. */
    @Value("${dynamodb.player-state-table-name}")
    private String playerStateTableName;

    /** Physical GameEvent table name from configuration. */
    @Value("${dynamodb.game-events-table-name}")
    private String gameEventsTableName;

    /** Physical Leaderboard table name from configuration. */
    @Value("${dynamodb.leaderboard-table-name}")
    private String leaderboardTableName;

    /**
     * Runs table creation, TTL enablement, and demo seeding on startup when
     * {@code dynamodb.create-resources} is {@code true}.
     *
     * @param dynamoDbAsyncClient low-level client for control-plane calls
     * @return runner executed after the context starts
     */
    @Bean
    @ConditionalOnProperty(name = "dynamodb.create-resources", havingValue = "true")
    public CommandLineRunner initializeDynamoDbTables(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> {
            createPlayerStateTable(dynamoDbAsyncClient);
            createGameEventTable(dynamoDbAsyncClient);
            enableTimeToLiveOnGameEvent(dynamoDbAsyncClient);
            createLeaderboardTable(dynamoDbAsyncClient);
            seedPlayerData(dynamoDbAsyncClient);
        };
    }

    /**
     * Verifies that all three tables exist on startup when {@code dynamodb.create-resources} is
     * {@code false} or absent. Never creates tables or seeds data, so production startup does not need
     * control-plane permissions.
     *
     * @param dynamoDbAsyncClient low-level client for {@code DescribeTable} calls
     * @return runner executed after the context starts
     */
    @Bean
    @ConditionalOnProperty(name = "dynamodb.create-resources", havingValue = "false", matchIfMissing = true)
    public CommandLineRunner verifyDynamoDbTables(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> {
            verifyTableExists(dynamoDbAsyncClient, playerStateTableName);
            verifyTableExists(dynamoDbAsyncClient, gameEventsTableName);
            verifyTableExists(dynamoDbAsyncClient, leaderboardTableName);
        };
    }

    /**
     * Confirms a single table exists, failing fast with a clear error when it does not.
     *
     * @param client    DynamoDB client
     * @param tableName table to describe
     * @throws IllegalStateException when the table is missing or the describe call fails
     */
    private void verifyTableExists(DynamoDbAsyncClient client, String tableName) {
        try {
            client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).join();
            logger.info("Verified DynamoDB table exists [tableName={}]", tableName);
        } catch (Exception e) {
            if (e.getCause() instanceof ResourceNotFoundException) {
                throw new IllegalStateException("Required DynamoDB table not found: " + tableName
                        + ". Provision it out of band, or set dynamodb.create-resources=true for local development", e);
            }
            throw new IllegalStateException("Failed to verify DynamoDB table " + tableName, e);
        }
    }

    /**
     * Declares PlayerState with base keys and {@code GSI_PLATFORM_PLAYERS}.
     *
     * @param client DynamoDB client
     */
    private void createPlayerStateTable(DynamoDbAsyncClient client) {
        GlobalSecondaryIndex platformGsi = GlobalSecondaryIndex.builder()
                .indexName("GSI_PLATFORM_PLAYERS")
                .keySchema(
                        KeySchemaElement.builder().attributeName("platform").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("lastUpdatedAt").keyType(KeyType.RANGE).build(),
                        KeySchemaElement.builder().attributeName("playerId").keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();

        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(playerStateTableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("platform").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("lastUpdatedAt").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("playerId").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .globalSecondaryIndexes(platformGsi)
                .build();

        createTableIfNotExists(client, request, playerStateTableName);
    }

    /**
     * Declares GameEvent with PK and SK and a DynamoDB stream ({@link StreamViewType#NEW_IMAGE}) for leaderboard projection.
     *
     * @param client DynamoDB client
     */
    private void createGameEventTable(DynamoDbAsyncClient client) {
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(gameEventsTableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .streamSpecification(StreamSpecification.builder()
                        .streamEnabled(true)
                        .streamViewType(StreamViewType.NEW_IMAGE)
                        .build())
                .build();

        createTableIfNotExists(client, request, gameEventsTableName);
    }

    /**
     * Enables TTL on the ttl attribute for GameEvent table.
     *
     * <p>Idempotent: when TTL is ENABLED or ENABLING, this step is skipped. The GameEvent table
     * is ACTIVE before this runs, so any other failure is a real error and surfaces instead of being
     * silently ignored.
     *
     * @param client DynamoDB client
     */
    private void enableTimeToLiveOnGameEvent(DynamoDbAsyncClient client) {
        TimeToLiveStatus currentStatus = client.describeTimeToLive(
                        DescribeTimeToLiveRequest.builder().tableName(gameEventsTableName).build())
                .join()
                .timeToLiveDescription()
                .timeToLiveStatus();
        if (currentStatus == TimeToLiveStatus.ENABLED || currentStatus == TimeToLiveStatus.ENABLING) {
            logger.info("GameEvent table TTL already enabled [tableName={}, status=skipped]",
                    gameEventsTableName);
            return;
        }
        client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                .tableName(gameEventsTableName)
                .timeToLiveSpecification(TimeToLiveSpecification.builder()
                        .attributeName("ttl")
                        .enabled(true)
                        .build())
                .build()).join();
        logger.info("GameEvent table TTL enabled [tableName={}, attributeName=ttl]",
                gameEventsTableName);
    }

    /**
     * Declares Leaderboard with PK and SK string keys for score ordering.
     *
     * @param client DynamoDB client
     */
    private void createLeaderboardTable(DynamoDbAsyncClient client) {
        CreateTableRequest request = CreateTableRequest.builder()
                .tableName(leaderboardTableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();

        createTableIfNotExists(client, request, leaderboardTableName);
    }

    /**
     * Creates a table unless it already exists, then waits for ACTIVE.
     *
     * <p>On AWS the CreateTable call returns while the table is still CREATING. This method ensures
     * the table is ready before TTL enablement or data write operations use it. See
     * {@link #waitUntilTableActive}.
     *
     * @param client     DynamoDB client
     * @param request    create table request
     * @param tableName  logical table name for logging
     */
    private void createTableIfNotExists(DynamoDbAsyncClient client, CreateTableRequest request, String tableName) {
        try {
            client.createTable(request).join();
            logger.info("DynamoDB table created [tableName={}]", tableName);
        } catch (Exception e) {
            if (e.getCause() instanceof ResourceInUseException) {
                logger.info("DynamoDB table already exists [tableName={}, status=skipped]",
                        tableName);
            } else {
                throw new IllegalStateException("Failed to create table " + tableName, e);
            }
        }
        // Whether we just created it or it already existed, do not proceed until it is ACTIVE.
        waitUntilTableActive(client, tableName);
    }

    /**
     * Blocks until the table is ACTIVE by polling DescribeTable via the SDK waiter.
     *
     * @param client    DynamoDB client
     * @param tableName name of table to wait on
     */
    private void waitUntilTableActive(DynamoDbAsyncClient client, String tableName) {
        client.waiter()
                .waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build())
                .join();
        logger.info("DynamoDB table active [tableName={}]", tableName);
    }

    /**
     * Inserts seed players, settings, and wallets using conditional puts.
     *
     * <p>PlayerState table is ACTIVE before this runs. A ConditionalCheckFailedException means the
     * row already exists and is skipped. Any other failure is rethrown so a broken seed fails startup
     * loudly instead of reporting false success. The summary log reports rows actually written, not
     * the seed candidate count.
     *
     * @param client DynamoDB client
     */
    private void seedPlayerData(DynamoDbAsyncClient client) {
        List<Map<String, AttributeValue>> profileSeeds = SeedPlayerData.samplePlayerProfilesAsMaps();
        List<Map<String, AttributeValue>> settingsSeeds = SeedPlayerData.samplePlayerSettingsAsMaps();
        List<Map<String, AttributeValue>> walletSeeds = SeedPlayerData.samplePlayerWalletsAsMaps();

        List<Map<String, AttributeValue>> allSeeds = new ArrayList<>(profileSeeds);
        allSeeds.addAll(settingsSeeds);
        allSeeds.addAll(walletSeeds);

        AtomicInteger insertedCount = new AtomicInteger();
        AtomicInteger skippedCount = new AtomicInteger();

        List<CompletableFuture<Void>> futures = allSeeds.stream()
                .map(item -> {
                    PutItemRequest putRequest = PutItemRequest.builder()
                            .tableName(playerStateTableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                            .build();
                    return client.putItem(putRequest)
                            .thenAccept(r -> {
                                insertedCount.incrementAndGet();
                                logger.debug("Seed data item inserted [partitionKey={}, sortKey={}]",
                                        item.get("PK").s(), item.get("SK").s());
                            })
                            .exceptionally(e -> {
                                Throwable cause = e.getCause() != null ? e.getCause() : e;
                                if (cause instanceof ConditionalCheckFailedException) {
                                    skippedCount.incrementAndGet();
                                    logger.debug("Seed data item already present [partitionKey={}, sortKey={}, status=skipped]",
                                            item.get("PK").s(), item.get("SK").s());
                                    return null;
                                }
                                throw new CompletionException(cause);
                            });
                })
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        logger.info("Seed player data loaded [insertedCount={}, skippedExistingCount={}, totalCandidates={}]",
                insertedCount.get(), skippedCount.get(), allSeeds.size());
    }
}
