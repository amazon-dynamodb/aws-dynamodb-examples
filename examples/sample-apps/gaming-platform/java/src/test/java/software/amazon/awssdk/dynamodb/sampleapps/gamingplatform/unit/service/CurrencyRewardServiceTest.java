package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.CurrencyRewardService;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Unit tests for {@link CurrencyRewardService}.
 *
 * <p>Uses mocked repositories and mappers to verify grant flows, idempotent replay on duplicate
 * events, and wallet-not-found handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class CurrencyRewardServiceTest {

    private static final String PLAYER_ID = "player-001";

    @Mock
    private PlayerStateRepository repository;

    @Mock
    private GameEventMapper gameEventMapper;

    private CurrencyRewardService service;

    /**
     * Wires the service under test with mocked collaborators.
     */
    @BeforeEach
    void setUp() {
        service = new CurrencyRewardService(repository, gameEventMapper);
    }

    @Test
    void grantCurrency_whenValidRequest_shouldComplete() {
        PlayerWallet wallet = buildWallet(500L, 1L);
        GameEvent rewardEvent = buildEvent("evt-1");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(
                eq(PLAYER_ID), eq(300L), eq(CurrencyEarnReason.MATCH_WIN), eq("req-1")))
                .thenReturn(rewardEvent);
        when(repository.earnCurrencyTransaction(eq(wallet), eq(300L), eq(rewardEvent)))
                .thenReturn(CompletableFuture.completedFuture(null));

        WalletEarnResponse response = service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(300L, CurrencyEarnReason.MATCH_WIN, "req-1")).join();

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.playerId()).isEqualTo(PLAYER_ID);
        assertThat(response.wallet().currencyBalance()).isEqualTo(800L);
        assertThat(response.wallet().version()).isEqualTo(2L);
        assertThat(response.earnEventId()).isEqualTo("evt-1");
        verify(repository).earnCurrencyTransaction(wallet, 300L, rewardEvent);
    }

    @Test
    void grantCurrency_whenDuplicateEvent_shouldReturnIdempotentReplay() {
        PlayerWallet wallet = buildWallet(500L, 1L);
        GameEvent rewardEvent = buildEvent("evt-dup");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(any(), anyLong(), any(), any()))
                .thenReturn(rewardEvent);

        CancellationReason okReason = CancellationReason.builder().code("None").build();
        CancellationReason failReason = CancellationReason.builder().code("ConditionalCheckFailed").build();
        TransactionCanceledException txEx = TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(List.of(okReason, failReason))
                .build();

        when(repository.earnCurrencyTransaction(any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(txEx)));

        WalletEarnResponse response = service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(300L, CurrencyEarnReason.MATCH_WIN, "req-dup")).join();

        assertThat(response.status()).isEqualTo("IDEMPOTENT_REPLAY");
        assertThat(response.earnEventId()).isEqualTo("evt-dup");
        assertThat(response.wallet().currencyBalance()).isEqualTo(500L);
    }

    @Test
    void grantCurrency_whenWalletVersionStale_shouldThrowStaleVersion() {
        PlayerWallet wallet = buildWallet(0L, 1L);
        GameEvent rewardEvent = buildEvent("evt-x");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(any(), anyLong(), any(), any()))
                .thenReturn(rewardEvent);

        CancellationReason walletFail = CancellationReason.builder().code("ConditionalCheckFailed").build();
        CancellationReason eventOk = CancellationReason.builder().code("None").build();
        TransactionCanceledException txEx = TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(List.of(walletFail, eventOk))
                .build();

        when(repository.earnCurrencyTransaction(any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(txEx)));

        assertThatThrownBy(() -> service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(100L, CurrencyEarnReason.ADMIN_GRANT, "req-y")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(StaleVersionException.class);
    }

    @Test
    void grantCurrency_whenWalletMissing_shouldThrowWalletNotFound() {
        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(100L, CurrencyEarnReason.DAILY_LOGIN, "req-z")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WalletNotFoundException.class);

        verify(repository, never()).earnCurrencyTransaction(any(), anyLong(), any());
    }

    @Test
    void grantCurrency_whenConvenienceOverloadUsed_shouldDelegateCorrectly() {
        PlayerWallet wallet = buildWallet(100L, 1L);
        GameEvent rewardEvent = buildEvent("evt-conv");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(
                eq(PLAYER_ID), eq(250L), eq(CurrencyEarnReason.LEVEL_UP_BONUS), eq("lvl-key")))
                .thenReturn(rewardEvent);
        when(repository.earnCurrencyTransaction(eq(wallet), eq(250L), eq(rewardEvent)))
                .thenReturn(CompletableFuture.completedFuture(null));

        WalletEarnResponse response = service.grantCurrency(
                PLAYER_ID, 250L, CurrencyEarnReason.LEVEL_UP_BONUS, "lvl-key").join();

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.wallet().currencyBalance()).isEqualTo(350L);
        assertThat(response.wallet().version()).isEqualTo(2L);
    }

    @Test
    void grantCurrency_whenTransactionConflictThenSuccess_shouldRetryAndComplete() {
        PlayerWallet wallet = buildWallet(500L, 1L);
        GameEvent rewardEvent = buildEvent("evt-conflict");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(any(), anyLong(), any(), any()))
                .thenReturn(rewardEvent);
        when(repository.earnCurrencyTransaction(any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(transactionConflict()))
                .thenReturn(CompletableFuture.completedFuture(null));

        WalletEarnResponse response = service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(300L, CurrencyEarnReason.MATCH_WIN, "req-conflict")).join();

        assertThat(response.status()).isEqualTo("COMPLETED");
        // Wallet is re-read and the transact rebuilt on each attempt.
        verify(repository, times(2)).getWallet(PLAYER_ID);
        verify(repository, times(2)).earnCurrencyTransaction(any(), anyLong(), any());
    }

    @Test
    void grantCurrency_whenConflictPersists_shouldExhaustRetriesAndThrow() {
        PlayerWallet wallet = buildWallet(500L, 1L);
        GameEvent rewardEvent = buildEvent("evt-loop");

        when(repository.getWallet(PLAYER_ID)).thenReturn(CompletableFuture.completedFuture(wallet));
        when(gameEventMapper.toCurrencyGrantEvent(any(), anyLong(), any(), any()))
                .thenReturn(rewardEvent);
        when(repository.earnCurrencyTransaction(any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(transactionConflict()));

        assertThatThrownBy(() -> service.grantCurrency(
                PLAYER_ID, new WalletEarnRequest(300L, CurrencyEarnReason.MATCH_WIN, "req-loop")).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TransactionCanceledException.class);

        verify(repository, times(3)).earnCurrencyTransaction(any(), anyLong(), any());
    }

    /**
     * Builds a {@link TransactionCanceledException} whose reasons indicate a serializable conflict.
     *
     * @return cancellation carrying {@code TransactionConflict} on both transact items
     */
    private static TransactionCanceledException transactionConflict() {
        return TransactionCanceledException.builder()
                .message("Transaction cancelled")
                .cancellationReasons(List.of(
                        CancellationReason.builder().code("TransactionConflict").build(),
                        CancellationReason.builder().code("TransactionConflict").build()))
                .build();
    }


    /**
     * Builds a {@link PlayerWallet} fixture for the shared test player.
     *
     * @param balance wallet balance before the grant
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
     * Builds a currency-grant {@link GameEvent} fixture with the given event id.
     *
     * @param eventId event identifier
     * @return populated grant event
     */
    private static GameEvent buildEvent(String eventId) {
        GameEvent e = new GameEvent();
        e.setPartitionKey(GameEvent.PK_PREFIX + PLAYER_ID);
        e.setSortKey(GameEvent.SK_PREFIX + "CURRENCY_GRANT#" + eventId);
        e.setEventId(eventId);
        e.setPlayerId(PLAYER_ID);
        e.setEventType("CURRENCY_GRANT");
        e.setCurrencyDelta(300L);
        return e;
    }
}
