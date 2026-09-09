package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.HighLevelDynamoDbLeaderboardRepository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

/**
 * Unit tests for {@link HighLevelDynamoDbLeaderboardRepository} with mocked table.
 *
 * <p>Verifies put and scoped query operations delegate to the enhanced client table with the
 * expected keys and limits.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class HighLevelDynamoDbLeaderboardRepositoryTest {

    @Mock
    private DynamoDbEnhancedAsyncClient enhancedClient;

    @Mock
    private DynamoDbAsyncTable<LeaderboardEntry> table;

    private HighLevelDynamoDbLeaderboardRepository repository;

    /**
     * Wires the repository under test with a mocked enhanced client.
     */
    @BeforeEach
    void setUp() {
        when(enhancedClient.table(any(), any(TableSchema.class))).thenReturn(table);
        repository = new HighLevelDynamoDbLeaderboardRepository(enhancedClient, "Leaderboard");
    }

    @Test
    void putLeaderboardEntry_whenValidEntry_shouldCallPutItem() {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPartitionKey("PK");
        entry.setSortKey("SK");
        when(table.putItem(entry)).thenReturn(CompletableFuture.completedFuture(null));

        repository.putLeaderboardEntry(entry).join();

        verify(table).putItem(entry);
    }

    @Test
    void deleteLeaderboardEntry_whenKeyProvided_shouldCallDeleteItemWithKey() {
        when(table.deleteItem(any(Key.class))).thenReturn(CompletableFuture.completedFuture(null));

        repository.deleteLeaderboardEntry("pk1", "sk1").join();

        ArgumentCaptor<Key> captor = ArgumentCaptor.forClass(Key.class);
        verify(table).deleteItem(captor.capture());
        assertThat(captor.getValue().partitionKeyValue().s()).isEqualTo("pk1");
        assertThat(captor.getValue().sortKeyValue().get().s()).isEqualTo("sk1");
    }

    @Test
    void queryTopN_whenEntriesExist_shouldCollectFirstPage() {
        LeaderboardEntry row = new LeaderboardEntry();
        row.setPlayerId("p");
        PagePublisher<LeaderboardEntry> publisher =
                PagePublisher.create(SdkPublisher.fromIterable(List.of(Page.create(List.of(row)))));
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(publisher);

        List<LeaderboardEntry> result = repository.queryTopN("SEASON#s#MODE#m", 10).join();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPlayerId()).isEqualTo("p");

        ArgumentCaptor<QueryEnhancedRequest> queryCaptor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);
        verify(table).query(queryCaptor.capture());
        QueryEnhancedRequest sent = queryCaptor.getValue();
        assertThat(sent.limit()).isEqualTo(10);
        assertThat(sent.scanIndexForward()).isFalse();
    }
}
