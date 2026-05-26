package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LowLevelDynamoDbLeaderboardRepository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Unit tests for {@link LowLevelDynamoDbLeaderboardRepository} with mocked client.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LowLevelDynamoDbLeaderboardRepositoryTest {

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void putLeaderboardEntry_whenValidEntry_shouldSendPutItem() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbLeaderboardRepository repository =
                new LowLevelDynamoDbLeaderboardRepository(client, "LB");

        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPartitionKey("PK#1");
        entry.setSortKey("SK#1");
        entry.setPlayerId("p");
        entry.setPlayerName("n");
        entry.setScore(1);

        repository.putLeaderboardEntry(entry).join();

        ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("LB");
        assertThat(captor.getValue().item().get("PK").s()).isEqualTo("PK#1");
        assertThat(captor.getValue().item().get("SK").s()).isEqualTo("SK#1");
    }

    @Test
    void deleteLeaderboardEntry_whenKeyProvided_shouldSendDeleteItem() {
        when(client.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LowLevelDynamoDbLeaderboardRepository repository =
                new LowLevelDynamoDbLeaderboardRepository(client, "LB");

        repository.deleteLeaderboardEntry("pk", "sk").join();

        ArgumentCaptor<DeleteItemRequest> captor = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(client).deleteItem(captor.capture());
        assertThat(captor.getValue().tableName()).isEqualTo("LB");
        assertThat(captor.getValue().key().get("PK").s()).isEqualTo("pk");
        assertThat(captor.getValue().key().get("SK").s()).isEqualTo("sk");
    }

    @Test
    void queryTopN_whenEntriesExist_shouldMapItems() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build()));

        LowLevelDynamoDbLeaderboardRepository repository =
                new LowLevelDynamoDbLeaderboardRepository(client, "LB");

        assertThat(repository.queryTopN("SEASON#x#MODE#y", 5).join()).isEmpty();

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(captor.capture());
        QueryRequest sent = captor.getValue();
        assertThat(sent.tableName()).isEqualTo("LB");
        assertThat(sent.limit()).isEqualTo(5);
        assertThat(sent.scanIndexForward()).isFalse();
        assertThat(sent.keyConditionExpression()).isEqualTo("PK = :pk");
        assertThat(sent.expressionAttributeValues())
                .isEqualTo(Map.of(":pk", AttributeValue.fromS(LeaderboardEntry.buildPartitionKey("SEASON#x#MODE#y"))));
    }
}
