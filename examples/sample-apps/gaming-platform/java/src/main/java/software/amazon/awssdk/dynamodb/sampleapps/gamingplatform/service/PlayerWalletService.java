package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetWalletResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;

/**
 * Exposes the player's soft currency wallet as a focused read endpoint.
 *
 * <p>The wallet ({@code SK = WALLET}) lives under the same {@code USER#<playerId>} partition
 * as the profile and settings rows, but carries its own {@code version} attribute so currency
 * writes (purchases, rewards) never conflict with progression writes on the profile's optimistic lock.
 *
 * <p>Currency balance writes are intentionally not exposed here. They occur only through
 * {@link PurchaseService} via {@code TransactWriteItems}. The service reads the wallet and
 * checks the balance upfront before the transaction as a fail-fast guard. DynamoDB then
 * enforces {@code currencyBalance >= :cost AND version = :expectedVersion} atomically as the
 * authoritative guard. This means neither insufficient-funds nor stale-version failures can
 * produce partial state.
 */
@Service
public class PlayerWalletService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerWalletService.class);

    /** PlayerState persistence implementation (wallet item). */
    private final PlayerStateRepository playerStateRepository;

    /** Maps wallet domain model to snapshot DTO. */
    private final PlayerWalletMapper playerWalletMapper;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerWalletMapper converts between domain model and DTO
     */
    public PlayerWalletService(PlayerStateRepository playerStateRepository, PlayerWalletMapper playerWalletMapper) {
        this.playerStateRepository = playerStateRepository;
        this.playerWalletMapper = playerWalletMapper;
    }

    /**
     * Returns the wallet slice for a player.
     *
     * <p>The response includes the {@code version} field so callers can detect if the balance
     * changed between reads, even though balance writes are not exposed through this service.
     *
     * @param playerId the internal player id
     * @return future of the wallet slice response
     * @throws WalletNotFoundException if no wallet item exists for the given id
     */
    public CompletableFuture<GetWalletResponse> getWallet(String playerId) {
        return playerStateRepository.getWallet(playerId).thenApply(wallet -> {
            if (wallet == null) {
                throw new WalletNotFoundException(playerId);
            }
            logger.debug("Retrieved player wallet [playerId={}]", playerId);
            return new GetWalletResponse(playerWalletMapper.toSnapshot(wallet));
        });
    }
}
