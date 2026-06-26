package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProfileSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.CurrencyRewardService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSnapshotService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.ProgressionService;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Unit tests for {@link ProgressionService}.
 *
 * <p>Uses mocked repositories and reward services to verify XP updates, level computation,
 * stale-version handling, and level-up bonus grants.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ProgressionServiceTest {

    private static final String PLAYER_ID = "player-001";

    @Mock
    private PlayerStateRepository playerStateRepository;

    @Mock
    private CurrencyRewardService currencyRewardService;

    @Mock
    private PlayerSnapshotService playerSnapshotService;

    private ProgressionService progressionService;

    private final TestableProgressionService progressionForLevelMath = new TestableProgressionService();

    /**
     * Wires the service under test with mocked collaborators.
     */
    @BeforeEach
    void setUp() {
        progressionService = new ProgressionService(
                playerStateRepository, currencyRewardService, playerSnapshotService);
    }

    @Test
    void computeLevel_whenXpNegative_shouldReturnOne() {
        assertThat(progressionForLevelMath.levelForXp(-500L)).isEqualTo(1);
    }

    @Test
    void computeLevel_whenXpZero_shouldReturnOne() {
        assertThat(progressionForLevelMath.levelForXp(0L)).isEqualTo(1);
    }

    @Test
    void computeLevel_whenJustPastTier_shouldIncreaseLevel() {
        assertThat(progressionForLevelMath.levelForXp(ProgressionService.XP_PER_LEVEL - 1)).isEqualTo(1);
        assertThat(progressionForLevelMath.levelForXp(ProgressionService.XP_PER_LEVEL)).isEqualTo(2);
        assertThat(progressionForLevelMath.levelForXp(ProgressionService.XP_PER_LEVEL + 100)).isEqualTo(2);
    }

    @Test
    void computeLevel_whenXpExceedsMax_shouldClampAtMaxLevel() {
        assertThat(progressionForLevelMath.levelForXp(
                ProgressionService.XP_PER_LEVEL * (ProgressionService.MAX_LEVEL + 10)))
                .isEqualTo(ProgressionService.MAX_LEVEL);
    }

    @Test
    void updateProgression_whenValidPatch_shouldReturnSnapshot() {
        PlayerProfile current = buildProfile(5000L, 1);
        PlayerProfile updated = buildProfile(5500L, 6);
        PlayerSnapshot snapshot = sampleSnapshot(5500L, 6, 600L, 1L);

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(current));
        when(playerStateRepository.updateProgression(eq(PLAYER_ID), eq(5500L), eq(6), eq(1L)))
                .thenReturn(CompletableFuture.completedFuture(updated));
        when(playerSnapshotService.load(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(snapshot));

        ProgressionUpdateRequest request = new ProgressionUpdateRequest(500L, "QUEST_REWARD", 1L);
        ProgressionUpdateResponse response = progressionService.updateProgression(PLAYER_ID, request).join();

        assertThat(response.profile()).isNotNull();
        assertThat(response.appliedEventId()).isNull();
    }

    @Test
    void updateProgression_whenVersionStale_shouldThrow() {
        PlayerProfile current = buildProfile(3000L, 1);

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(current));
        when(playerStateRepository.updateProgression(eq(PLAYER_ID), eq(3200L), eq(4), eq(1L)))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(ConditionalCheckFailedException.builder()
                                .message("version mismatch").build())));

        ProgressionUpdateRequest request = new ProgressionUpdateRequest(200L, null, 1L);

        assertThatThrownBy(() -> progressionService.updateProgression(PLAYER_ID, request).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StaleVersionException.class);
    }

    @Test
    void updateProgression_whenXpThresholdCrossed_shouldLevelUp() {
        PlayerProfile current = buildProfile(900L, 1);
        PlayerProfile updated = buildProfile(1100L, 2);
        WalletEarnResponse earnResponse = new WalletEarnResponse(
                PLAYER_ID,
                new WalletSnapshot(ProgressionService.LEVEL_UP_BONUS, 2L),
                "COMPLETED",
                "evt-1");
        PlayerSnapshot snapshot = sampleSnapshot(1100L, 2, ProgressionService.LEVEL_UP_BONUS, 2L);

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(current));
        when(playerStateRepository.updateProgression(eq(PLAYER_ID), eq(1100L), eq(2), eq(1L)))
                .thenReturn(CompletableFuture.completedFuture(updated));
        when(currencyRewardService.grantCurrency(
                eq(PLAYER_ID), eq(ProgressionService.LEVEL_UP_BONUS), eq(CurrencyEarnReason.LEVEL_UP_BONUS), anyString()))
                .thenReturn(CompletableFuture.completedFuture(earnResponse));
        when(playerSnapshotService.load(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(snapshot));

        ProgressionUpdateRequest request = new ProgressionUpdateRequest(200L, "LEVEL_COMPLETE", 1L);
        ProgressionUpdateResponse response = progressionService.updateProgression(PLAYER_ID, request).join();

        assertThat(response.profile()).isNotNull();
        verify(currencyRewardService).grantCurrency(
                eq(PLAYER_ID), eq(ProgressionService.LEVEL_UP_BONUS), eq(CurrencyEarnReason.LEVEL_UP_BONUS), anyString());
    }

    @Test
    void updateProgression_whenPlayerNotFound_shouldThrow() {
        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        ProgressionUpdateRequest request = new ProgressionUpdateRequest(100L, null, 1L);

        assertThatThrownBy(() -> progressionService.updateProgression(PLAYER_ID, request).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PlayerNotFoundException.class);
    }

    /**
     * Builds a {@link PlayerSnapshot} fixture reflecting post-update progression and wallet state.
     *
     * @param xp            total experience points
     * @param level         current level derived from XP
     * @param balance       wallet soft-currency balance
     * @param walletVersion wallet optimistic-lock version
     * @return composed player snapshot
     */
    private static PlayerSnapshot sampleSnapshot(long xp, int level, long balance, long walletVersion) {
        return new PlayerSnapshot(
                PLAYER_ID,
                new ProfileSnapshot("TestPlayer", "STEAM", level, xp, "2025-01-01T00:00:00Z", 2),
                new WalletSnapshot(balance, walletVersion),
                new SettingsSnapshot(true, "en", "PUBLIC", 1));
    }

    /**
     * Package-private subclass that exposes {@link ProgressionService#computeLevel(long)} for
     * pure level-math assertions without repository I/O.
     */
    private static final class TestableProgressionService extends ProgressionService {

        TestableProgressionService() {
            super(mock(PlayerStateRepository.class),
                    mock(CurrencyRewardService.class),
                    mock(PlayerSnapshotService.class));
        }

        /**
         * Delegates to the protected level computation used by {@link ProgressionService}.
         *
         * @param xp total experience points
         * @return computed player level
         */
        int levelForXp(long xp) {
            return computeLevel(xp);
        }
    }

    /**
     * Builds a {@link PlayerProfile} fixture with the given XP total and version.
     *
     * @param xp      total experience points
     * @param version optimistic-lock version
     * @return populated profile
     */
    private static PlayerProfile buildProfile(long xp, long version) {
        PlayerProfile p = new PlayerProfile();
        p.setPartitionKey(PlayerProfile.PK_PREFIX + PLAYER_ID);
        p.setSortKey(PlayerProfile.SK_PROFILE);
        p.setPlayerId(PLAYER_ID);
        p.setPlayerName("TestPlayer");
        p.setPlatform("STEAM");
        p.setTotalExperience(xp);
        p.setCurrentLevel((int) (xp / 1000) + 1);
        p.setVersion(version);
        p.setLastUpdatedAt("2025-01-01T00:00:00Z");
        return p;
    }
}
