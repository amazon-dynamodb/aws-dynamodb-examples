package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
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
 * Creates all three DynamoDB tables, enables TTL and Streams, and seeds sample data at startup.
 *
 * <p>Table creation is idempotent. {@link ResourceInUseException} is caught and logged when
 * the table already exists. Seed inserts use {@code attribute_not_exists(PK)} to avoid
 * overwriting existing data on restarts.
 */
@Configuration
public class DynamoDbTableInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableInitializer.class);

    /** Physical PlayerState table name from configuration. */
    @Value("${dynamodb.player-state-table-name}")
    private String playerStateTableName;

    /** Physical GameEvents table name from configuration. */
    @Value("${dynamodb.game-events-table-name}")
    private String gameEventsTableName;

    /** Physical LeaderboardAggregate table name from configuration. */
    @Value("${dynamodb.leaderboard-table-name}")
    private String leaderboardTableName;

    /**
     * Runs table initialization on application startup.
     *
     * @param dynamoDbAsyncClient low-level client for control-plane calls
     * @return runner executed after the context starts
     */
    @Bean
    public CommandLineRunner initializeDynamoDbTables(DynamoDbAsyncClient dynamoDbAsyncClient) {
        return args -> {
            createPlayerStateTable(dynamoDbAsyncClient);
            createGameEventsTable(dynamoDbAsyncClient);
            enableTimeToLiveOnGameEvents(dynamoDbAsyncClient);
            createLeaderboardTable(dynamoDbAsyncClient);
            seedPlayerData(dynamoDbAsyncClient);
        };
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
     * Declares GameEvents with PK and SK and a DynamoDB stream ({@link StreamViewType#NEW_IMAGE}) for leaderboard projection.
     *
     * @param client DynamoDB client
     */
    private void createGameEventsTable(DynamoDbAsyncClient client) {
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
     * Enables TTL on the {@code ttl} attribute for the GameEvents table when possible.
     *
     * @param client DynamoDB client
     */
    private void enableTimeToLiveOnGameEvents(DynamoDbAsyncClient client) {
        try {
            client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(gameEventsTableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .attributeName("ttl")
                            .enabled(true)
                            .build())
                    .build()).join();
            logger.info("GameEvents table TTL enabled [tableName={}, attributeName=ttl]",
                    gameEventsTableName);
        } catch (Exception e) {
            logger.debug("GameEvents table TTL enablement skipped [tableName={}, detail={}]",
                    gameEventsTableName, e.getMessage());
        }
    }

    /**
     * Declares LeaderboardAggregate with composite string sort key for score ordering.
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
     * Creates a table unless it already exists.
     *
     * @param client     DynamoDB client
     * @param request    create-table request
     * @param tableName  logical name for logging
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
    }

    /**
     * Inserts seed players, their default settings, and wallets when missing using conditional puts.
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

        List<CompletableFuture<Void>> futures = allSeeds.stream()
                .map(item -> {
                    PutItemRequest putRequest = PutItemRequest.builder()
                            .tableName(playerStateTableName)
                            .item(item)
                            .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                            .build();
                    return client.putItem(putRequest)
                            .thenAccept(r -> logger.debug("Seed data item inserted [partitionKey={}, sortKey={}]",
                                    item.get("PK").s(), item.get("SK").s()))
                            .exceptionally(e -> {
                                logger.debug("Seed data item already present [partitionKey={}, sortKey={}, status=skipped]",
                                        item.get("PK").s(), item.get("SK").s());
                                return null;
                            });
                })
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        logger.info("Seed player data loaded [profileCount={}, settingsCount={}, walletCount={}]",
                profileSeeds.size(), settingsSeeds.size(), walletSeeds.size());
    }
}
