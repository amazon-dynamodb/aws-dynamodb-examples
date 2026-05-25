package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.SettingsSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;

/**
 * Converts between {@link PlayerWallet} / {@link PlayerSettings} domain objects and REST snapshot DTOs.
 *
 * @see PlayerWallet
 * @see PlayerSettings
 * @see WalletSnapshot
 * @see SettingsSnapshot
 */
@Component
public class PlayerWalletMapper {

    /**
     * Starter currency balance granted to every new player on registration.
     *
     * <p>Example: a player registered for the first time receives {@value #INITIAL_BALANCE}
     * soft currency and can immediately make a basic purchase without a separate earn call.
     */
    public static final long INITIAL_BALANCE = 1000L;

    /**
     * Creates a default {@link PlayerWallet} for a new player registration.
     *
     * <p>The wallet is pre-funded with {@link #INITIAL_BALANCE} soft currency so
     * newly registered players can make their first purchase without needing a separate
     * earn operation.
     *
     * @param playerId the player id
     * @return wallet seeded with the starter-pack balance
     */
    public PlayerWallet defaultWallet(String playerId) {
        PlayerWallet wallet = new PlayerWallet();
        wallet.setPartitionKey(PlayerProfile.PK_PREFIX + playerId);
        wallet.setSortKey(PlayerWallet.SK_WALLET);
        wallet.setEntityType(PlayerWallet.ENTITY_TYPE);
        wallet.setPlayerId(playerId);
        wallet.setCurrencyBalance(INITIAL_BALANCE);
        return wallet;
    }

    /**
     * Converts a wallet model to its snapshot DTO.
     *
     * @param wallet domain wallet
     * @return wallet snapshot for API responses
     */
    public WalletSnapshot toSnapshot(PlayerWallet wallet) {
        return new WalletSnapshot(wallet.getCurrencyBalance(), wallet.getVersion());
    }
}
