package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;

/**
 * Unit tests for {@link PlayerWalletMapper}.
 *
 * <p>Verifies that {@link PlayerWalletMapper#defaultWallet(String)} seeds the starter balance
 * and that {@link PlayerWalletMapper#toSnapshot(PlayerWallet)} maps balance and version fields.
 */
@Tag("unit")
class PlayerWalletMapperTest {

    private final PlayerWalletMapper mapper = new PlayerWalletMapper();

    @Test
    void defaultWallet_whenNewPlayerId_seedsStarterBalanceAndKeys() {
        PlayerWallet wallet = mapper.defaultWallet("player-1");

        assertThat(wallet.getPartitionKey()).isEqualTo(PlayerProfile.PK_PREFIX + "player-1");
        assertThat(wallet.getSortKey()).isEqualTo(PlayerWallet.SK_WALLET);
        assertThat(wallet.getEntityType()).isEqualTo(PlayerWallet.ENTITY_TYPE);
        assertThat(wallet.getPlayerId()).isEqualTo("player-1");
        assertThat(wallet.getCurrencyBalance()).isEqualTo(PlayerWalletMapper.INITIAL_BALANCE);
    }

    @Test
    void toSnapshot_whenWalletPresent_mapsBalanceAndVersionWithoutPlayerId() {
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPlayerId("player-1");
        wallet.setCurrencyBalance(2500);
        wallet.setVersion(7);

        WalletSnapshot response = mapper.toSnapshot(wallet);

        assertThat(response.currencyBalance()).isEqualTo(2500);
        assertThat(response.version()).isEqualTo(7);
    }
}
