package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.config.DynamoDbTableInitializer;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveResponse;

/**
 * Unit tests for startup table creation and seed orchestration via {@link DynamoDbTableInitializer}.
 */
@Tag("unit")
class DynamoDbTableInitializerTest {

    static DynamoDbAsyncClient dynamoDbAsyncClient;

    private ApplicationContextRunner contextRunner;

    /**
     * Resets the shared mock client and stubs the happy-path create/TTL/seed calls
     * before each test.
     */
    @BeforeEach
    void setUp() {
        dynamoDbAsyncClient = mock(DynamoDbAsyncClient.class);
        stubCreateTableHappyPath();
        when(dynamoDbAsyncClient.updateTimeToLive(any(UpdateTimeToLiveRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateTimeToLiveResponse.builder().build()));
        when(dynamoDbAsyncClient.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(PutItemResponse.builder().build()));

        contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(DynamoDbTableInitializer.class, DynamoClientInjection.class);
    }

    @Test
    void run_whenApplicationStarts_shouldIssueCreatesTtlAndSeeds() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.create-resources=true",
                        "dynamodb.player-state-table-name=TblPlayerState",
                        "dynamodb.game-events-table-name=TblGameEvents",
                        "dynamodb.leaderboard-table-name=TblLeaderboard")
                .run(context -> {
                    assertThat(context.getBean(DynamoDbTableInitializer.class)).isNotNull();
                    context.getBean("initializeDynamoDbTables", CommandLineRunner.class).run();
                });

        verify(dynamoDbAsyncClient, times(3)).createTable(any(CreateTableRequest.class));
        verify(dynamoDbAsyncClient, times(1)).updateTimeToLive(any(UpdateTimeToLiveRequest.class));
        verify(dynamoDbAsyncClient, times(15)).putItem(any(PutItemRequest.class));

        ArgumentCaptor<CreateTableRequest> captor = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(dynamoDbAsyncClient, times(3)).createTable(captor.capture());
        List<CreateTableRequest> creates = captor.getAllValues();
        assertThat(creates.stream().map(CreateTableRequest::tableName)).containsExactlyInAnyOrder(
                "TblPlayerState", "TblGameEvents", "TblLeaderboard");
    }

    @Test
    void buildPlayerStateGsi_whenConfigured_shouldHaveCompositeSortKeyWithLastUpdatedAtAndPlayerId() {
        contextRunner
                .withPropertyValues(
                        "dynamodb.create-resources=true",
                        "dynamodb.player-state-table-name=TblPlayerState",
                        "dynamodb.game-events-table-name=TblGameEvents",
                        "dynamodb.leaderboard-table-name=TblLeaderboard")
                .run(context -> context.getBean("initializeDynamoDbTables", CommandLineRunner.class).run());

        ArgumentCaptor<CreateTableRequest> captor = ArgumentCaptor.forClass(CreateTableRequest.class);
        verify(dynamoDbAsyncClient, times(3)).createTable(captor.capture());

        CreateTableRequest playerStateCreate = captor.getAllValues().stream()
                .filter(r -> "TblPlayerState".equals(r.tableName()))
                .findFirst()
                .orElseThrow();

        // Assert GSI exists
        assertThat(playerStateCreate.globalSecondaryIndexes()).hasSize(1);
        GlobalSecondaryIndex gsi = playerStateCreate.globalSecondaryIndexes().getFirst();
        assertThat(gsi.indexName()).isEqualTo(PlayerProfile.GSI_PLATFORM_PLAYERS);

        // Assert composite key schema: platform (HASH), lastUpdatedAt (RANGE), playerId (RANGE)
        List<KeySchemaElement> gsiKeys = gsi.keySchema();
        assertThat(gsiKeys).hasSize(3);
        assertThat(gsiKeys.get(0).attributeName()).isEqualTo("platform");
        assertThat(gsiKeys.get(0).keyType()).isEqualTo(KeyType.HASH);
        assertThat(gsiKeys.get(1).attributeName()).isEqualTo("lastUpdatedAt");
        assertThat(gsiKeys.get(1).keyType()).isEqualTo(KeyType.RANGE);
        assertThat(gsiKeys.get(2).attributeName()).isEqualTo("playerId");
        assertThat(gsiKeys.get(2).keyType()).isEqualTo(KeyType.RANGE);

        // Assert all key attributes are declared in attribute definitions
        List<String> definedAttrs = playerStateCreate.attributeDefinitions().stream()
                .map(AttributeDefinition::attributeName)
                .toList();
        assertThat(definedAttrs).contains("platform", "lastUpdatedAt", "playerId");
    }

    @Test
    void createTableIfNotExists_whenTableAlreadyExists_shouldNotFailStartup() {
        ResourceInUseException cause = ResourceInUseException.builder()
                .message("Table exists")
                .build();

        CompletableFuture<CreateTableResponse> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(cause);

        when(dynamoDbAsyncClient.createTable(any(CreateTableRequest.class)))
                .thenReturn(failedFuture);

        contextRunner
                .withPropertyValues(
                        "dynamodb.create-resources=true",
                        "dynamodb.player-state-table-name=P",
                        "dynamodb.game-events-table-name=G",
                        "dynamodb.leaderboard-table-name=L")
                .run(context -> context.getBean("initializeDynamoDbTables", CommandLineRunner.class).run());

        verify(dynamoDbAsyncClient, times(15)).putItem(any(PutItemRequest.class));
    }

    @Test
    void run_whenCreateResourcesFalse_shouldVerifyTablesAndNotCreateOrSeed() {
        when(dynamoDbAsyncClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DescribeTableResponse.builder().build()));

        contextRunner
                .withPropertyValues(
                        "dynamodb.create-resources=false",
                        "dynamodb.player-state-table-name=P",
                        "dynamodb.game-events-table-name=G",
                        "dynamodb.leaderboard-table-name=L")
                .run(context -> {
                    assertThat(context.containsBean("initializeDynamoDbTables")).isFalse();
                    context.getBean("verifyDynamoDbTables", CommandLineRunner.class).run();
                });

        verify(dynamoDbAsyncClient, times(3)).describeTable(any(DescribeTableRequest.class));
        verify(dynamoDbAsyncClient, never()).createTable(any(CreateTableRequest.class));
        verify(dynamoDbAsyncClient, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void run_whenCreateResourcesMissing_shouldDefaultToVerifyOnly() {
        when(dynamoDbAsyncClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DescribeTableResponse.builder().build()));

        contextRunner
                .withPropertyValues(
                        "dynamodb.player-state-table-name=P",
                        "dynamodb.game-events-table-name=G",
                        "dynamodb.leaderboard-table-name=L")
                .run(context -> {
                    assertThat(context.containsBean("initializeDynamoDbTables")).isFalse();
                    assertThat(context.containsBean("verifyDynamoDbTables")).isTrue();
                    context.getBean("verifyDynamoDbTables", CommandLineRunner.class).run();
                });

        verify(dynamoDbAsyncClient, times(3)).describeTable(any(DescribeTableRequest.class));
        verify(dynamoDbAsyncClient, never()).createTable(any(CreateTableRequest.class));
    }

    @Test
    void run_whenVerifyAndTableMissing_shouldFailFast() {
        ResourceNotFoundException cause = ResourceNotFoundException.builder()
                .message("Requested resource not found")
                .build();
        CompletableFuture<DescribeTableResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(cause);
        when(dynamoDbAsyncClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(failed);

        contextRunner
                .withPropertyValues(
                        "dynamodb.create-resources=false",
                        "dynamodb.player-state-table-name=P",
                        "dynamodb.game-events-table-name=G",
                        "dynamodb.leaderboard-table-name=L")
                .run(context -> {
                    CommandLineRunner verifyRunner =
                            context.getBean("verifyDynamoDbTables", CommandLineRunner.class);
                    assertThatThrownBy(verifyRunner::run)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Required DynamoDB table not found");
                });

        verify(dynamoDbAsyncClient, never()).createTable(any(CreateTableRequest.class));
    }

    /**
     * Stubs the shared mock client to return a successful {@link CreateTableResponse} for any request.
     */
    private void stubCreateTableHappyPath() {
        CompletableFuture<CreateTableResponse> success =
                CompletableFuture.completedFuture(CreateTableResponse.builder().build());
        when(dynamoDbAsyncClient.createTable(any(CreateTableRequest.class)))
                .thenReturn(success);
    }

    /** Supplies {@link DynamoDbAsyncClient} for {@link DynamoDbTableInitializer}'s runner. */
    @Configuration(proxyBeanMethods = false)
    static class DynamoClientInjection {

        /**
         * Returns the client assigned from the active test fixture.
         *
         * @return mocked {@link DynamoDbAsyncClient}
         */
        @Bean
        DynamoDbAsyncClient dynamoDbAsyncClient() {
            return DynamoDbTableInitializerTest.dynamoDbAsyncClient;
        }
    }
}
