package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlatformPlayersResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSummary;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LobbyService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LobbyService}.
 *
 * <p>Uses mocked repository and mapper collaborators to verify batch lobby summaries, missing id
 * reporting, and platform browse delegation.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class LobbyServiceTest {

    @Mock
    private PlayerStateRepository repository;

    @Mock
    private PlayerMapper mapper;

    @InjectMocks
    private LobbyService service;

    @Test
    void shouldReturnSummariesAndMissingIds() {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId("player-1");
        profile.setPlayerName("AlphaWolf");
        profile.setCurrentLevel(10);
        profile.setLastUpdatedAt("2026-01-15T10:00:00Z");

        PlayerSummary summary = new PlayerSummary("player-1", "AlphaWolf", 10, "2026-01-15T10:00:00Z");

        when(repository.batchGetPlayers(List.of("player-1", "player-unknown")))
                .thenReturn(CompletableFuture.completedFuture(List.of(profile)));
        when(mapper.toSummary(profile)).thenReturn(summary);

        LobbySummariesResponse result = service.getLobbySummaries(
                new LobbySummariesRequest(List.of("player-1", "player-unknown")));

        assertThat(result.summaries()).hasSize(1);
        assertThat(result.summaries().getFirst().playerId()).isEqualTo("player-1");
        assertThat(result.missingPlayerIds()).containsExactly("player-unknown");
    }

    @Test
    void shouldReturnEmptyWhenNoPlayersFound() {
        when(repository.batchGetPlayers(List.of("unknown-1", "unknown-2")))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        LobbySummariesResponse result = service.getLobbySummaries(
                new LobbySummariesRequest(List.of("unknown-1", "unknown-2")));

        assertThat(result.summaries()).isEmpty();
        assertThat(result.missingPlayerIds()).containsExactlyInAnyOrder("unknown-1", "unknown-2");
    }

    @Test
    void shouldReturnPlatformPlayers() {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId("player-1");
        profile.setPlayerName("AlphaWolf");
        profile.setCurrentLevel(10);
        profile.setLastUpdatedAt("2026-01-15T10:00:00Z");

        PlayerSummary summary = new PlayerSummary("player-1", "AlphaWolf", 10, "2026-01-15T10:00:00Z");

        when(repository.queryPlayersByPlatform("PC", 20))
                .thenReturn(CompletableFuture.completedFuture(List.of(profile)));
        when(mapper.toSummary(profile)).thenReturn(summary);

        PlatformPlayersResponse result = service.getPlayersByPlatform("PC", 20);

        assertThat(result.platform()).isEqualTo("PC");
        assertThat(result.players()).hasSize(1);
        assertThat(result.players().getFirst().playerId()).isEqualTo("player-1");
    }

    @Test
    void shouldClampLimitToMax() {
        when(repository.queryPlayersByPlatform("PC", 50))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        PlatformPlayersResponse result = service.getPlayersByPlatform("PC", 999);

        assertThat(result.platform()).isEqualTo("PC");
        assertThat(result.players()).isEmpty();
    }

    @Test
    void shouldClampLimitToMin() {
        when(repository.queryPlayersByPlatform("PC", 1))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        PlatformPlayersResponse result = service.getPlayersByPlatform("PC", -5);

        assertThat(result.platform()).isEqualTo("PC");
        assertThat(result.players()).isEmpty();
    }

    @Test
    void shouldThrowOnInvalidPlatform() {
        assertThatThrownBy(() -> service.getPlayersByPlatform("XBOX", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid platform");
    }
}
