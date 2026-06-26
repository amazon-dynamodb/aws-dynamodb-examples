package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.SeedPlayerData;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
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
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Test support component that deletes and recreates all three DynamoDB tables
 * between integration test runs for full isolation.
 */
@Component
@Profile("test")
public class DynamoDbTableTestReset {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbTableTestReset.class);

    private final DynamoDbAsyncClient client;

    private final String playerStateTableName;

    private final String gameEventsTableName;

    private final String leaderboardTableName;

    /**
     * Creates the reset helper with the tables to manage.
     *
     * @param client               DynamoDB async client
     * @param playerStateTableName PlayerState table name
     * @param gameEventsTableName  GameEvent table name
     * @param leaderboardTableName Leaderboard table name
     */
    public DynamoDbTableTestReset(DynamoDbAsyncClient client,
                                   @Value("${dynamodb.player-state-table-name}") String playerStateTableName,
                                   @Value("${dynamodb.game-events-table-name}") String gameEventsTableName,
                                   @Value("${dynamodb.leaderboard-table-name}") String leaderboardTableName) {
        this.client = client;
        this.playerStateTableName = playerStateTableName;
        this.gameEventsTableName = gameEventsTableName;
        this.leaderboardTableName = leaderboardTableName;
    }

    /**
     * Drops and recreates all tables with their GSIs, streams, and TTL configuration,
     * then seeds sample player data.
     *
     * <p>Call this in a {@code @BeforeEach} method to guarantee full isolation between tests.
     */
    public void deleteRecreateAndSeed() {
        deleteTableIfExists(playerStateTableName);
        deleteTableIfExists(gameEventsTableName);
        deleteTableIfExists(leaderboardTableName);

        createPlayerStateTable();
        createGameEventTable();
        enableTtlOnGameEvent();
        createLeaderboardTable();
        seedPlayers();
    }

    /**
     * Deletes the named table and waits for deletion to complete.
     * Ignores {@link ResourceNotFoundException}
     * so the method is safe to call when the table does not yet exist.
     *
     * @param tableName physical table name to delete
     */
    private void deleteTableIfExists(String tableName) {
        try {
            client.deleteTable(b -> b.tableName(tableName)).join();
            client.waiter().waitUntilTableNotExists(b -> b.tableName(tableName)).join();
        } catch (Exception e) {
            if (!(e.getCause() instanceof ResourceNotFoundException)) {
                log.warn("Error deleting table {}: {}", tableName, e.getMessage());
            }
        }
    }

    /**
     * Creates the PlayerState table with composite primary key and the
     * {@code GSI_PLATFORM_PLAYERS} global secondary index.
     */
    private void createPlayerStateTable() {
        GlobalSecondaryIndex gsi = GlobalSecondaryIndex.builder()
                .indexName(PlayerProfile.GSI_PLATFORM_PLAYERS)
                .keySchema(
                        KeySchemaElement.builder().attributeName("platform").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("lastUpdatedAt").keyType(KeyType.RANGE).build(),
                        KeySchemaElement.builder().attributeName("playerId").keyType(KeyType.RANGE).build())
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build();

        client.createTable(CreateTableRequest.builder()
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
                .globalSecondaryIndexes(gsi)
                .build()).join();
    }

    /**
     * Creates the GameEvent table with composite primary key and a
     * {@code NEW_IMAGE} DynamoDB stream enabled for leaderboard projection.
     */
    private void createGameEventTable() {
        client.createTable(CreateTableRequest.builder()
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
                .build()).join();
    }

    /**
     * Enables TTL on the {@code ttl} attribute for the GameEvent table.
     * Failures are logged at debug level because local DynamoDB may not support TTL.
     */
    private void enableTtlOnGameEvent() {
        try {
            client.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(gameEventsTableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .attributeName("ttl")
                            .enabled(true)
                            .build())
                    .build()).join();
        } catch (Exception e) {
            log.debug("TTL enablement skipped: {}", e.getMessage());
        }
    }

    /**
     * Creates the Leaderboard table with composite string sort key
     * for zero-padded score ordering.
     */
    private void createLeaderboardTable() {
        client.createTable(CreateTableRequest.builder()
                .tableName(leaderboardTableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("PK").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("SK").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("PK").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("SK").attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build()).join();
    }

    /**
     * Seeds sample player profiles, default settings, and wallets using conditional puts
     * so existing items are not overwritten.
     *
     * <p>Seeds are sourced from {@link SeedPlayerData} and written in parallel.
     */
    private void seedPlayers() {
        List<Map<String, AttributeValue>> profileSeeds = SeedPlayerData.samplePlayerProfilesAsMaps();
        List<Map<String, AttributeValue>> settingsSeeds = SeedPlayerData.samplePlayerSettingsAsMaps();
        List<Map<String, AttributeValue>> walletSeeds = SeedPlayerData.samplePlayerWalletsAsMaps();

        List<Map<String, AttributeValue>> allSeeds = new ArrayList<>(profileSeeds);
        allSeeds.addAll(settingsSeeds);
        allSeeds.addAll(walletSeeds);

        List<CompletableFuture<Void>> futures = allSeeds.stream()
                .map(item -> client.putItem(PutItemRequest.builder()
                                .tableName(playerStateTableName)
                                .item(item)
                                .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                                .build())
                        .thenAccept(r -> { })
                        .exceptionally(e -> null))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }
}
