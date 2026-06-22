package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerAlreadyExistsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerRegistrationService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSnapshotService;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlayerRegistrationService}.
 *
 * <p>Uses mocked mappers and repositories to verify new registrations, idempotent replay on
 * duplicate platform identity, and conflicting-identity errors.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlayerRegistrationServiceTest {

    @Mock
    private PlayerStateRepository repository;

    @Mock
    private PlayerMapper mapper;

    @Mock
    private PlayerSettingsMapper settingsMapper;

    @Mock
    private PlayerWalletMapper walletMapper;

    @Mock
    private PlayerSnapshotService playerSnapshotService;

    @InjectMocks
    private PlayerRegistrationService service;

    @Test
    void registerPlayer_whenNewRequest_shouldRegisterPlayer() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-123", "TestPlayer");
        PlayerProfile draft = buildProfile("player-1", "PC", "steam-123");
        draft.setVersion(0);
        PlayerSettings defaultSettings = new PlayerSettings();
        PlayerWallet defaultWallet = new PlayerWallet();
        PlayerSnapshot snapshot = sampleSnapshot("player-1", "TestPlayer", 0, 1);

        when(mapper.toProfile(request)).thenReturn(draft);
        when(settingsMapper.defaultSettings("player-1")).thenReturn(defaultSettings);
        when(walletMapper.defaultWallet("player-1")).thenReturn(defaultWallet);
        when(repository.createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(playerSnapshotService.load("player-1")).thenReturn(snapshot);

        RegisterPlayerResponse result = service.registerPlayer(request);

        assertThat(result.created()).isTrue();
        assertThat(result.playerId()).isEqualTo("player-1");
        verify(repository).createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet);
        verify(playerSnapshotService).load("player-1");
    }

    @Test
    void registerPlayer_whenIdempotentReplay_shouldReturnExisting() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-123", "TestPlayer");
        PlayerProfile profile = buildProfile("player-1", "PC", "steam-123");
        PlayerSettings defaultSettings = new PlayerSettings();
        PlayerWallet defaultWallet = new PlayerWallet();
        PlayerProfile existing = buildProfile("player-1", "PC", "steam-123");
        PlayerSnapshot snapshot = sampleSnapshot("player-1", "TestPlayer", 1000, 3);

        when(mapper.toProfile(request)).thenReturn(profile);
        when(settingsMapper.defaultSettings("player-1")).thenReturn(defaultSettings);
        when(walletMapper.defaultWallet("player-1")).thenReturn(defaultWallet);
        when(repository.createPlayerWithSettingsAndWallet(profile, defaultSettings, defaultWallet)).thenReturn(
                CompletableFuture.failedFuture(transactionCanceled()));
        when(repository.getPlayer("player-1")).thenReturn(CompletableFuture.completedFuture(existing));
        when(playerSnapshotService.load("player-1")).thenReturn(snapshot);

        RegisterPlayerResponse result = service.registerPlayer(request);

        assertThat(result.created()).isFalse();
        assertThat(result.playerId()).isEqualTo("player-1");
    }

    @Test
    void registerPlayer_whenDuplicateWithDifferentData_shouldThrow() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-123", "TestPlayer");
        PlayerProfile profile = buildProfile("player-1", "PC", "steam-123");
        PlayerSettings defaultSettings = new PlayerSettings();
        PlayerWallet defaultWallet = new PlayerWallet();
        PlayerProfile existing = buildProfile("player-1", "IOS", "apple-456");

        when(mapper.toProfile(request)).thenReturn(profile);
        when(settingsMapper.defaultSettings("player-1")).thenReturn(defaultSettings);
        when(walletMapper.defaultWallet("player-1")).thenReturn(defaultWallet);
        when(repository.createPlayerWithSettingsAndWallet(profile, defaultSettings, defaultWallet)).thenReturn(
                CompletableFuture.failedFuture(transactionCanceled()));
        when(repository.getPlayer("player-1")).thenReturn(CompletableFuture.completedFuture(existing));

        assertThatThrownBy(() -> service.registerPlayer(request))
                .isInstanceOf(PlayerAlreadyExistsException.class);
    }

    @Test
    void registerPlayer_whenTransactionConflictThenSuccess_shouldRetryAndCreate() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-123", "TestPlayer");
        PlayerProfile draft = buildProfile("player-1", "PC", "steam-123");
        PlayerSettings defaultSettings = new PlayerSettings();
        PlayerWallet defaultWallet = new PlayerWallet();
        PlayerSnapshot snapshot = sampleSnapshot("player-1", "TestPlayer", 0, 1);

        when(mapper.toProfile(request)).thenReturn(draft);
        when(settingsMapper.defaultSettings("player-1")).thenReturn(defaultSettings);
        when(walletMapper.defaultWallet("player-1")).thenReturn(defaultWallet);
        when(repository.createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet))
                .thenReturn(CompletableFuture.failedFuture(transactionConflict()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(playerSnapshotService.load("player-1")).thenReturn(snapshot);

        RegisterPlayerResponse result = service.registerPlayer(request);

        assertThat(result.created()).isTrue();
        verify(repository, times(2))
                .createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet);
        // A conflict is retried, not treated as an existing player, so no read-back occurs.
        verify(repository, never()).getPlayer(any());
    }

    @Test
    void registerPlayer_whenConflictPersists_shouldExhaustRetriesAndThrow() {
        RegisterPlayerRequest request = new RegisterPlayerRequest("PC", "steam-123", "TestPlayer");
        PlayerProfile draft = buildProfile("player-1", "PC", "steam-123");
        PlayerSettings defaultSettings = new PlayerSettings();
        PlayerWallet defaultWallet = new PlayerWallet();

        when(mapper.toProfile(request)).thenReturn(draft);
        when(settingsMapper.defaultSettings("player-1")).thenReturn(defaultSettings);
        when(walletMapper.defaultWallet("player-1")).thenReturn(defaultWallet);
        when(repository.createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet))
                .thenReturn(CompletableFuture.failedFuture(transactionConflict()));

        assertThatThrownBy(() -> service.registerPlayer(request))
                .isInstanceOf(TransactionCanceledException.class);

        verify(repository, times(3))
                .createPlayerWithSettingsAndWallet(draft, defaultSettings, defaultWallet);
        verify(repository, never()).getPlayer(any());
    }

    /**
     * Builds a {@link PlayerSnapshot} fixture returned after registration completes.
     *
     * @param playerId       player identifier
     * @param name           display name
     * @param xp             total experience
     * @param profileVersion profile optimistic-lock version
     * @return composed player snapshot
     */
    private static PlayerSnapshot sampleSnapshot(String playerId, String name, long xp, long profileVersion) {
        return new PlayerSnapshot(
                playerId,
                new ProfileSnapshot(name, "PC", 1, xp, "2026-01-01T00:00:00Z", profileVersion),
                new WalletSnapshot(0, 1),
                new SettingsSnapshot(true, "en", "PUBLIC", 1));
    }

    /**
     * Builds a {@link PlayerProfile} draft or existing row for the given platform identity.
     *
     * @param playerId       player identifier
     * @param platform       gaming platform code
     * @param platformUserId external platform user handle
     * @return populated profile
     */
    private static PlayerProfile buildProfile(String playerId, String platform, String platformUserId) {
        PlayerProfile profile = new PlayerProfile();
        profile.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        profile.setSortKey(PlayerProfile.SK_PROFILE);
        profile.setPlayerId(playerId);
        profile.setPlatform(platform);
        profile.setPlatformUserId(platformUserId);
        profile.setPlayerName("TestPlayer");
        profile.setCurrentLevel(1);
        profile.setTotalExperience(0);
        profile.setVersion(1);
        profile.setLastUpdatedAt("2026-01-01T00:00:00Z");
        return profile;
    }

    /**
     * Builds a {@link TransactionCanceledException} simulating a conditional registration failure.
     *
     * @return transaction cancelled exception without detailed reasons
     */
    private static TransactionCanceledException transactionCanceled() {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .build();
    }

    /**
     * Builds a {@link TransactionCanceledException} whose reasons indicate a serializable conflict.
     *
     * @return cancellation carrying {@code TransactionConflict} reasons
     */
    private static TransactionCanceledException transactionConflict() {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(
                        CancellationReason.builder().code("TransactionConflict").build(),
                        CancellationReason.builder().code("TransactionConflict").build(),
                        CancellationReason.builder().code("TransactionConflict").build())
                .build();
    }
}
