package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetProfileResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;

/**
 * Provides read-only access to player profiles.
 *
 * <p>Loads the {@code PROFILE} sort key from the PlayerState table. Wallet and settings
 * are exposed through their dedicated endpoints and included in full snapshot write responses.
 *
 * <p>This service is intentionally read-only. Profile writes (registration, progression)
 * and wallet writes (purchases, rewards) are handled by their respective services, which
 * own the conditional write and transaction logic.
 */
@Service
public class PlayerProfileService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerProfileService.class);

    /** PlayerState persistence implementation. */
    private final PlayerStateRepository playerStateRepository;

    /** Maps profiles to REST DTOs. */
    private final PlayerMapper playerMapper;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerMapper converts between domain models and DTOs
     */
    public PlayerProfileService(PlayerStateRepository playerStateRepository, PlayerMapper playerMapper) {
        this.playerStateRepository = playerStateRepository;
        this.playerMapper = playerMapper;
    }

    /**
     * Retrieves the profile slice for the given player id.
     *
     * @param playerId the internal player id
     * @return future of the profile slice response DTO
     * @throws PlayerNotFoundException if no profile exists for the given id
     */
    public CompletableFuture<GetProfileResponse> getProfile(String playerId) {
        return playerStateRepository.getPlayer(playerId).thenApply(profile -> {
            if (profile == null) {
                throw new PlayerNotFoundException(playerId);
            }
            logger.debug("Retrieved player profile [playerId={}]", playerId);
            return new GetProfileResponse(playerMapper.toProfileSnapshot(profile));
        });
    }
}
