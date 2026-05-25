package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetProfileResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlayerProfileService}.
 *
 * <p>Uses mocked repository and mapper collaborators to verify profile slice mapping and
 * player-not-found handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlayerProfileServiceTest {

    @Mock
    private PlayerStateRepository repository;

    @Mock
    private PlayerMapper mapper;

    @InjectMocks
    private PlayerProfileService service;

    @Test
    void getProfile_whenPlayerExists_returnsProfileSliceWrapper() {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId("player-1");
        profile.setPlayerName("AlphaWolf");
        profile.setPlatform("PC");
        profile.setCurrentLevel(10);
        profile.setTotalExperience(3500);
        profile.setLastUpdatedAt("2026-01-15T10:00:00Z");
        profile.setVersion(1);

        ProfileSnapshot expected = new ProfileSnapshot(
                "AlphaWolf", "PC", 10, 3500, "2026-01-15T10:00:00Z", 1);

        when(repository.getPlayer("player-1")).thenReturn(CompletableFuture.completedFuture(profile));
        when(mapper.toProfileSnapshot(profile)).thenReturn(expected);

        GetProfileResponse result = service.getProfile("player-1");

        assertThat(result.profile().playerName()).isEqualTo("AlphaWolf");
        assertThat(result.profile().currentLevel()).isEqualTo(10);
    }

    @Test
    void getProfile_whenPlayerMissing_throwsPlayerNotFound() {
        when(repository.getPlayer("unknown")).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.getProfile("unknown"))
                .isInstanceOf(PlayerNotFoundException.class);
    }
}
