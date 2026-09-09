package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetWalletResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerWalletService;

/**
 * Unit tests for {@link PlayerWalletService} wallet reads.
 *
 * <p>Uses a mocked {@link PlayerStateRepository} to verify wallet slice mapping and
 * wallet-not-found handling.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlayerWalletServiceTest {

    @Mock
    private PlayerStateRepository repository;

    @InjectMocks
    private PlayerWalletMapper mapper;

    private PlayerWalletService service;

    /**
     * Wires the service under test with a real mapper and mocked repository.
     */
    @BeforeEach
    void setUp() {
        service = new PlayerWalletService(repository, mapper);
    }

    @Test
    void getWallet_whenWalletExists_shouldReturnWalletSliceWrapper() {
        PlayerWallet wallet = buildWallet("player-1", 1200L, 3L);
        when(repository.getWallet("player-1")).thenReturn(CompletableFuture.completedFuture(wallet));

        GetWalletResponse response = service.getWallet("player-1").join();

        assertThat(response.wallet().currencyBalance()).isEqualTo(1200L);
        assertThat(response.wallet().version()).isEqualTo(3L);
    }

    @Test
    void getWallet_whenWalletMissing_shouldThrowWalletNotFound() {
        when(repository.getWallet("missing")).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> service.getWallet("missing").join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(WalletNotFoundException.class);
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
        wallet.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        wallet.setSortKey(PlayerWallet.SK_WALLET);
        wallet.setPlayerId(playerId);
        wallet.setCurrencyBalance(balance);
        wallet.setVersion(version);
        return wallet;
    }
}
