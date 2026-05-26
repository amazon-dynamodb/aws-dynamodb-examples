package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSnapshotService;

/**
 * Unit tests for {@link PlayerSnapshotService}.
 *
 * <p>Uses a mocked {@link PlayerStateRepository} to verify snapshot assembly and missing-state
 * error handling for profile, wallet, and settings slices.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlayerSnapshotServiceTest {

    @Mock
    private PlayerStateRepository repository;

    private PlayerSnapshotService service;

    /**
     * Wires the service under test with real mappers and a mocked repository.
     */
    @BeforeEach
    void setUp() {
        PlayerMapper playerMapper = new PlayerMapper(new PlayerWalletMapper(), new PlayerSettingsMapper());
        service = new PlayerSnapshotService(repository, playerMapper);
    }

    @Test
    void load_whenProfileWalletAndSettingsExist_shouldComposeFullSnapshot() {
        String playerId = "player-1";
        PlayerProfile profile = buildProfile(playerId);
        PlayerWallet wallet = buildWallet(playerId, 1200L, 2L);
        PlayerSettings settings = buildSettings(playerId, 3L);

        when(repository.getPlayer(playerId)).thenReturn(CompletableFuture.completedFuture(profile));
        when(repository.getWallet(playerId)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(repository.getSettings(playerId)).thenReturn(CompletableFuture.completedFuture(settings));

        PlayerSnapshot snapshot = service.load(playerId);

        assertThat(snapshot.playerId()).isEqualTo(playerId);
        assertThat(snapshot.profile().playerName()).isEqualTo("AlphaWolf");
        assertThat(snapshot.wallet().currencyBalance()).isEqualTo(1200L);
        assertThat(snapshot.settings().preferredLanguage()).isEqualTo("en");
    }

    @Test
    void load_whenProfileMissing_shouldThrowPlayerNotFound() {
        when(repository.getPlayer("missing")).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.load("missing"))
                .isInstanceOf(PlayerNotFoundException.class);
    }

    @Test
    void load_whenWalletMissing_shouldThrowWalletNotFound() {
        String playerId = "player-1";
        when(repository.getPlayer(playerId)).thenReturn(CompletableFuture.completedFuture(buildProfile(playerId)));
        when(repository.getWallet(playerId)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.load(playerId))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void load_whenSettingsMissing_shouldThrowPlayerNotFound() {
        String playerId = "player-1";
        when(repository.getPlayer(playerId)).thenReturn(CompletableFuture.completedFuture(buildProfile(playerId)));
        when(repository.getWallet(playerId)).thenReturn(CompletableFuture.completedFuture(buildWallet(playerId, 0, 1)));
        when(repository.getSettings(playerId)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.load(playerId))
                .isInstanceOf(PlayerNotFoundException.class);
    }

    /**
     * Builds a minimal {@link PlayerProfile} fixture for snapshot assembly tests.
     *
     * @param playerId player identifier
     * @return populated profile
     */
    private static PlayerProfile buildProfile(String playerId) {
        PlayerProfile profile = new PlayerProfile();
        profile.setPlayerId(playerId);
        profile.setPlayerName("AlphaWolf");
        profile.setPlatform("PC");
        profile.setCurrentLevel(10);
        profile.setTotalExperience(3500);
        profile.setLastUpdatedAt("2026-01-15T10:00:00Z");
        profile.setVersion(1);
        return profile;
    }

    /**
     * Builds a {@link PlayerWallet} fixture with the given balance and version.
     *
     * @param playerId player identifier
     * @param balance  soft-currency balance
     * @param version  optimistic-lock version
     * @return populated wallet
     */
    private static PlayerWallet buildWallet(String playerId, long balance, long version) {
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPlayerId(playerId);
        wallet.setCurrencyBalance(balance);
        wallet.setVersion(version);
        return wallet;
    }

    /**
     * Builds a {@link PlayerSettings} fixture with default preference values.
     *
     * @param playerId player identifier
     * @param version  optimistic-lock version
     * @return populated settings
     */
    private static PlayerSettings buildSettings(String playerId, long version) {
        PlayerSettings settings = new PlayerSettings();
        settings.setPlayerId(playerId);
        settings.setNotificationsEnabled(true);
        settings.setPreferredLanguage("en");
        settings.setProfileVisibility("PUBLIC");
        settings.setVersion(version);
        return settings;
    }
}
