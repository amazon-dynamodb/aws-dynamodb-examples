package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.LeaderboardMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;

/**
 * Unit tests for {@link LeaderboardMapper}.
 *
 * <p>Verifies sort-key padding for score ordering and mapping between {@link LeaderboardEntry}
 * and leaderboard DTOs.
 */
@Tag("unit")
class LeaderboardMapperTest {

    private final LeaderboardMapper mapper = new LeaderboardMapper();

    @Test
    void toEntryDto_whenEntryProvided_shouldAssignRank() {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPlayerId("p1");
        entry.setPlayerName("A");
        entry.setScore(100);

        assertThat(mapper.toEntryDto(entry, 1).rank()).isEqualTo(1);
        assertThat(mapper.toEntryDto(entry, 5).rank()).isEqualTo(5);
        assertThat(mapper.toEntryDto(entry, 3).playerId()).isEqualTo("p1");
        assertThat(mapper.toEntryDto(entry, 3).score()).isEqualTo(100);
    }

    @Test
    void toResponse_whenEntriesProvided_shouldMapScopeAndOrderedRanks() {
        LeaderboardEntry e1 = new LeaderboardEntry();
        e1.setPlayerId("a");
        e1.setPlayerName("A");
        e1.setScore(10);
        LeaderboardEntry e2 = new LeaderboardEntry();
        e2.setPlayerId("b");
        e2.setPlayerName("B");
        e2.setScore(20);

        LeaderboardResponse response = mapper.toResponse("SCOPE#1", List.of(e1, e2));
        assertThat(response.scope()).isEqualTo("SCOPE#1");
        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).rank()).isEqualTo(1);
        assertThat(response.entries().get(0).playerId()).isEqualTo("a");
        assertThat(response.entries().get(1).rank()).isEqualTo(2);
    }
}
