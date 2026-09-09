package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardEntryDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.LeaderboardMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LeaderboardRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LeaderboardQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LeaderboardQueryService}.
 *
 * <p>Uses mocked repository and mapper collaborators to verify scoped queries, rank assignment,
 * and empty-scope handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LeaderboardQueryServiceTest {

    @Mock
    private LeaderboardRepository repository;

    @Mock
    private LeaderboardMapper mapper;

    @InjectMocks
    private LeaderboardQueryService service;

    @Test
    void getTopEntries_whenEntriesExist_shouldReturnTopEntries() {
        String scope = "SEASON#default#MODE#ranked";
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPlayerId("player-1");
        entry.setPlayerName("AlphaWolf");
        entry.setScore(2500);

        List<LeaderboardEntry> entries = List.of(entry);
        LeaderboardResponse expected = new LeaderboardResponse(scope,
                List.of(new LeaderboardEntryDto(1, "player-1", "AlphaWolf", 2500)));

        when(repository.queryTopN(scope, 10)).thenReturn(CompletableFuture.completedFuture(entries));
        when(mapper.toResponse(scope, entries)).thenReturn(expected);

        LeaderboardResponse result = service.getTopN(scope, 10).join();

        assertThat(result.scope()).isEqualTo(scope);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().playerId()).isEqualTo("player-1");
    }

    @Test
    void getTopEntries_whenLimitBelowMinimum_shouldClampToMinimum() {
        String scope = "SEASON#default#MODE#ranked";
        when(repository.queryTopN(scope, 1)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mapper.toResponse(eq(scope), anyList())).thenReturn(new LeaderboardResponse(scope, List.of()));

        service.getTopN(scope, -5);

        verify(repository).queryTopN(scope, 1);
    }

    @Test
    void getTopEntries_whenLimitAboveMaximum_shouldClampToMaximum() {
        String scope = "SEASON#default#MODE#ranked";
        when(repository.queryTopN(scope, 100)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mapper.toResponse(eq(scope), anyList())).thenReturn(new LeaderboardResponse(scope, List.of()));

        service.getTopN(scope, 500);

        verify(repository).queryTopN(scope, 100);
    }

    @Test
    void getTopEntries_whenNoEntries_shouldReturnEmpty() {
        String scope = "SEASON#empty#MODE#ranked";
        LeaderboardResponse expected = new LeaderboardResponse(scope, List.of());

        when(repository.queryTopN(scope, 10)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(mapper.toResponse(scope, List.of())).thenReturn(expected);

        LeaderboardResponse result = service.getTopN(scope, 10).join();

        assertThat(result.entries()).isEmpty();
    }
}
