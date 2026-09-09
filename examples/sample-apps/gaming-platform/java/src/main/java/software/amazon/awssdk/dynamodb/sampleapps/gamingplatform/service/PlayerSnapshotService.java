package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlayerSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;

/**
 * Loads the full player snapshot composed from PROFILE, WALLET, and SETTINGS items.
 *
 * <p>Used by write services so write responses always return a consistent player state shape.
 *
 * <p>Returns {@link CompletableFuture} so the write services can compose without blocking Tomcat
 * worker threads.
 */
@Service
public class PlayerSnapshotService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerSnapshotService.class);

    /** PlayerState persistence implementation. */
    private final PlayerStateRepository playerStateRepository;

    /** Maps domain entities to snapshot DTOs. */
    private final PlayerMapper playerMapper;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerMapper          converts domain models to snapshot DTOs
     */
    public PlayerSnapshotService(PlayerStateRepository playerStateRepository, PlayerMapper playerMapper) {
        this.playerStateRepository = playerStateRepository;
        this.playerMapper = playerMapper;
    }

    /**
     * Loads profile, wallet, and settings for the given player id.
     *
     * @param playerId the internal player id
     * @return future of the full player snapshot DTO
     * @throws PlayerNotFoundException if no profile or settings exist for the given id
     * @throws WalletNotFoundException if the profile exists but the wallet row is missing
     */
    public CompletableFuture<PlayerSnapshot> load(String playerId) {
        return playerStateRepository.getPlayer(playerId).thenCompose(profile -> {
            if (profile == null) {
                throw new PlayerNotFoundException(playerId);
            }
            return playerStateRepository.getWallet(playerId).thenCompose(wallet -> {
                if (wallet == null) {
                    throw new WalletNotFoundException(playerId);
                }
                return playerStateRepository.getSettings(playerId).thenApply(settings -> {
                    if (settings == null) {
                        throw new PlayerNotFoundException(playerId);
                    }
                    logger.debug("Loaded player snapshot [playerId={}]", playerId);
                    return playerMapper.toPlayerSnapshot(profile, wallet, settings);
                });
            });
        });
    }
}
