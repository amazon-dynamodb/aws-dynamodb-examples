package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InsufficientFundsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSnapshotService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PurchaseService;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Unit tests for {@link PurchaseService}.
 *
 * <p>Uses mocked repositories and mappers to verify successful purchases, insufficient funds,
 * idempotent replay, stale versions, and missing-player errors.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    private static final String PLAYER_ID = "player-002";

    @Mock
    private PlayerStateRepository playerStateRepository;

    @Mock
    private GameEventMapper gameEventMapper;

    @Mock
    private PlayerSnapshotService playerSnapshotService;

    @InjectMocks
    private PurchaseService purchaseService;

    @Test
    void completePurchase_whenFundsAvailable_shouldComplete() {
        PlayerProfile profile = buildProfile(1);
        PlayerWallet wallet = buildWallet(5000L, 1);
        GameEvent event = buildEvent();
        PurchaseRequest request = new PurchaseRequest("sword-01", 500L, "req-abc-123");
        PlayerSnapshot snapshot = sampleSnapshot(4500L, 2L);

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(playerStateRepository.getWallet(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toPurchaseEvent(PLAYER_ID, request)).thenReturn(event);
        when(playerStateRepository.purchaseTransaction(eq(wallet), eq(500L), eq("sword-01"), eq(event)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(playerSnapshotService.load(PLAYER_ID)).thenReturn(snapshot);

        PurchaseResponse response = purchaseService.executePurchase(PLAYER_ID, request);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.purchaseEventId()).isEqualTo(event.getEventId());
        assertThat(response.wallet().currencyBalance()).isEqualTo(4500L);
        verify(playerStateRepository).purchaseTransaction(eq(wallet), eq(500L), eq("sword-01"), eq(event));
    }

    @Test
    void completePurchase_whenInsufficientFunds_shouldReject() {
        PlayerProfile profile = buildProfile(1);
        PlayerWallet wallet = buildWallet(100L, 1);
        PurchaseRequest request = new PurchaseRequest("epic-armor", 500L, "req-xyz-789");

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(playerStateRepository.getWallet(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(wallet));

        assertThatThrownBy(() -> purchaseService.executePurchase(PLAYER_ID, request))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void completePurchase_whenBalanceTooLow_shouldFailFast() {
        PlayerProfile profile = buildProfile(1);
        PlayerWallet wallet = buildWallet(0L, 1);
        PurchaseRequest request = new PurchaseRequest("potion", 50L, "req-fail-fast");

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(playerStateRepository.getWallet(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(wallet));

        assertThatThrownBy(() -> purchaseService.executePurchase(PLAYER_ID, request))
                .isInstanceOf(InsufficientFundsException.class);

        verifyNoInteractions(gameEventMapper);
    }

    @Test
    void completePurchase_whenPlayerNotFound_shouldThrow() {
        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        PurchaseRequest request = new PurchaseRequest("item-01", 100L, "req-not-found");

        assertThatThrownBy(() -> purchaseService.executePurchase(PLAYER_ID, request))
                .isInstanceOf(PlayerNotFoundException.class);
    }

    @Test
    void completePurchase_whenWalletUpdateConditionallyFails_shouldThrowStaleVersion() {
        PlayerProfile profile = buildProfile(7);
        PlayerWallet wallet = buildWallet(5000L, 7);
        GameEvent event = buildEvent();
        PurchaseRequest request = new PurchaseRequest("sword-01", 500L, "req-stale-version");

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(playerStateRepository.getWallet(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toPurchaseEvent(PLAYER_ID, request)).thenReturn(event);

        TransactionCanceledException txEx = TransactionCanceledException.builder()
                .cancellationReasons(
                        CancellationReason.builder().code("ConditionalCheckFailed").build())
                .message("Transaction cancelled")
                .build();

        when(playerStateRepository.purchaseTransaction(eq(wallet), eq(500L), eq("sword-01"), eq(event)))
                .thenReturn(CompletableFuture.failedFuture(txEx));

        assertThatThrownBy(() -> purchaseService.executePurchase(PLAYER_ID, request))
                .isInstanceOf(StaleVersionException.class)
                .satisfies(ex -> {
                    StaleVersionException sve = (StaleVersionException) ex;
                    assertThat(sve.getPlayerId()).isEqualTo(PLAYER_ID);
                    assertThat(sve.getExpectedVersion()).isEqualTo(7L);
                });
    }

    @Test
    void completePurchase_whenDuplicateEvent_shouldReturnIdempotentReplay() {
        PlayerProfile profile = buildProfile(1);
        PlayerWallet wallet = buildWallet(5000L, 1);
        GameEvent event = buildEvent();
        PurchaseRequest request = new PurchaseRequest("sword-01", 500L, "req-dup");
        PlayerSnapshot snapshot = sampleSnapshot(4500L, 2L);

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(playerStateRepository.getWallet(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toPurchaseEvent(PLAYER_ID, request)).thenReturn(event);

        TransactionCanceledException txEx = TransactionCanceledException.builder()
                .cancellationReasons(
                        CancellationReason.builder().code("None").build(),
                        CancellationReason.builder().code("ConditionalCheckFailed").build())
                .message("Transaction cancelled")
                .build();

        when(playerStateRepository.purchaseTransaction(eq(wallet), eq(500L), eq("sword-01"), eq(event)))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(txEx)));
        when(playerSnapshotService.load(PLAYER_ID)).thenReturn(snapshot);

        PurchaseResponse response = purchaseService.executePurchase(PLAYER_ID, request);

        assertThat(response.status()).isEqualTo("IDEMPOTENT_REPLAY");
    }

    /**
     * Builds a {@link PlayerSnapshot} fixture with the given wallet balance and version.
     *
     * @param balance       post-purchase soft-currency balance
     * @param walletVersion wallet optimistic-lock version
     * @return composed player snapshot
     */
    private static PlayerSnapshot sampleSnapshot(long balance, long walletVersion) {
        return new PlayerSnapshot(
                PLAYER_ID,
                new ProfileSnapshot("PurchasePlayer", "XBOX", 3, 2000L, "2025-01-01T00:00:00Z", 2L),
                new WalletSnapshot(balance, walletVersion),
                new SettingsSnapshot(true, "en", "PUBLIC", 1L));
    }

    /**
     * Builds a {@link PlayerProfile} fixture for the shared test player.
     *
     * @param version optimistic-lock version
     * @return populated profile
     */
    private static PlayerProfile buildProfile(long version) {
        PlayerProfile p = new PlayerProfile();
        p.setPartitionKey(PlayerProfile.PK_PREFIX + PLAYER_ID);
        p.setSortKey(PlayerProfile.SK_PROFILE);
        p.setPlayerId(PLAYER_ID);
        p.setPlayerName("PurchasePlayer");
        p.setPlatform("XBOX");
        p.setTotalExperience(2000L);
        p.setCurrentLevel(3);
        p.setVersion(version);
        p.setLastUpdatedAt("2025-01-01T00:00:00Z");
        return p;
    }

    /**
     * Builds a {@link PlayerWallet} fixture for the shared test player.
     *
     * @param balance soft-currency balance before the purchase
     * @param version optimistic-lock version
     * @return populated wallet
     */
    private static PlayerWallet buildWallet(long balance, long version) {
        PlayerWallet w = new PlayerWallet();
        w.setPartitionKey(PlayerProfile.PK_PREFIX + PLAYER_ID);
        w.setSortKey(PlayerWallet.SK_WALLET);
        w.setPlayerId(PLAYER_ID);
        w.setCurrencyBalance(balance);
        w.setVersion(version);
        return w;
    }

    /**
     * Builds a purchase {@link GameEvent} fixture used by transaction stubs.
     *
     * @return populated purchase event
     */
    private static GameEvent buildEvent() {
        GameEvent e = new GameEvent();
        e.setPartitionKey(GameEvent.PK_PREFIX + PLAYER_ID);
        e.setSortKey(GameEvent.SK_PREFIX + "2025-01-01T00:00:00Z#evt-001");
        e.setEventId("evt-001");
        e.setPlayerId(PLAYER_ID);
        e.setEventType("PURCHASE");
        e.setRecordedAt("2025-01-01T00:00:00Z");
        e.setItemId("sword-01");
        e.setCurrencyDelta(-500L);
        return e;
    }
}
