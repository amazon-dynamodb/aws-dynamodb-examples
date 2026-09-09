package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbTableInitializer;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * Unit tests for {@link DynamoDbTableInitializer} and {@code dynamodb.create-resources} wiring.
 */
@Tag("unit")
public class DynamoDbTableInitializerTest {

    private static final String TABLE_NAME = "JavaInstantPayments";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DynamoDbTableInitializer.class, MockDynamoDbAsyncClientConfiguration.class)
            .withPropertyValues("dynamodb.table-name=" + TABLE_NAME);

    /**
     * Restores the mock client to a default stub that reports an active table before each test.
     */
    @BeforeEach
    void resetMockClient() {
        MockDynamoDbAsyncClientConfiguration.resetToDefault();
    }

    @Test
    void initializeDynamoDbTable_whenCreateResourcesTrue_shouldRegisterCreationRunner() {
        contextRunner
                .withPropertyValues("dynamodb.create-resources=true")
                .run(context -> {
                    assertThat(context).hasBean("initializeDynamoDbTable");
                    assertThat(context).doesNotHaveBean("verifyDynamoDbTableExists");
                });
    }

    @Test
    void verifyDynamoDbTableExists_whenCreateResourcesAbsent_shouldRegisterVerificationRunnerOnly() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("initializeDynamoDbTable");
            assertThat(context).hasBean("verifyDynamoDbTableExists");
        });
    }

    @Test
    void verifyDynamoDbTableExists_whenTableActive_shouldSucceed() {
        stubDescribeTable(TableStatus.ACTIVE);

        contextRunner
                .withPropertyValues("dynamodb.create-resources=false")
                .run(context -> {
                    CommandLineRunner runner = context.getBean("verifyDynamoDbTableExists", CommandLineRunner.class);
                    runner.run(new String[] {});
                });
    }

    @Test
    void verifyDynamoDbTableExists_whenTableMissing_shouldFailFast() {
        DynamoDbAsyncClient mockClient = mock(DynamoDbAsyncClient.class);
        when(mockClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ResourceNotFoundException.builder()
                                .message("Table not found: " + TABLE_NAME)
                                .build()));
        MockDynamoDbAsyncClientConfiguration.setClient(mockClient);

        contextRunner
                .withPropertyValues("dynamodb.create-resources=false")
                .run(context -> {
                    CommandLineRunner runner = context.getBean("verifyDynamoDbTableExists", CommandLineRunner.class);
                    assertThatThrownBy(() -> runner.run(new String[] {}))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("table not found")
                            .hasMessageContaining(TABLE_NAME)
                            .hasMessageContaining("dynamodb.create-resources=true");
                });
    }

    @Test
    void verifyDynamoDbTableExists_whenTableNotActive_shouldFailFast() {
        stubDescribeTable(TableStatus.CREATING);

        contextRunner
                .withPropertyValues("dynamodb.create-resources=false")
                .run(context -> {
                    CommandLineRunner runner = context.getBean("verifyDynamoDbTableExists", CommandLineRunner.class);
                    assertThatThrownBy(() -> runner.run(new String[] {}))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("not ACTIVE")
                            .hasMessageContaining(TABLE_NAME)
                            .hasMessageContaining("CREATING");
                });
    }

    /**
     * Installs a stub client whose {@code describeTable} reports the given table status.
     *
     * @param status table status the verification runner should observe
     */
    private static void stubDescribeTable(TableStatus status) {
        MockDynamoDbAsyncClientConfiguration.setClient(stubDescribeTableClient(status));
    }

    /**
     * Builds a mocked {@link DynamoDbAsyncClient} that returns a table with the given status.
     *
     * @param status table status to report from {@code describeTable}
     * @return mock client preconfigured for a single describe call
     */
    private static DynamoDbAsyncClient stubDescribeTableClient(TableStatus status) {
        DynamoDbAsyncClient mockClient = mock(DynamoDbAsyncClient.class);
        DescribeTableResponse response = DescribeTableResponse.builder()
                .table(TableDescription.builder()
                        .tableName(TABLE_NAME)
                        .tableStatus(status)
                        .build())
                .build();
        when(mockClient.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        return mockClient;
    }

    /**
     * Test configuration that exposes a swappable mock {@link DynamoDbAsyncClient} bean. Tests set the
     * stub they need before starting the context so the initializer wires against controlled behaviour.
     */
    @Configuration
    static class MockDynamoDbAsyncClientConfiguration {

        private static DynamoDbAsyncClient client;

        /**
         * Replaces the client returned by the bean factory method.
         *
         * @param dynamoDbAsyncClient stub client to expose to the context
         */
        static void setClient(DynamoDbAsyncClient dynamoDbAsyncClient) {
            client = dynamoDbAsyncClient;
        }

        /**
         * Resets the client to a stub that reports an active table.
         */
        static void resetToDefault() {
            client = defaultActiveTableClient();
        }

        /**
         * Builds the default stub client used between tests.
         *
         * @return mock client reporting an active table
         */
        private static DynamoDbAsyncClient defaultActiveTableClient() {
            return stubDescribeTableClient(TableStatus.ACTIVE);
        }

        /**
         * Exposes the currently configured stub client as a Spring bean.
         *
         * @return the active stub client, lazily initialised to the default when unset
         */
        @Bean
        DynamoDbAsyncClient dynamoDbAsyncClient() {
            if (client == null) {
                resetToDefault();
            }
            return client;
        }
    }
}
