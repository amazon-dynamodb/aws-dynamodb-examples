package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Reads and updates player settings (privacy, notifications, language).
 *
 * <p>Settings live in a separate DynamoDB item ({@code SK = SETTINGS}) under the same
 * partition as the player profile, so updates here never conflict with progression
 * or purchase writes on the {@code SK = PROFILE} item.
 */
@Service
public class PlayerSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerSettingsService.class);

    /** PlayerState persistence implementation. */
    private final PlayerStateRepository playerStateRepository;

    /** Maps settings models to DTOs. */
    private final PlayerSettingsMapper playerSettingsMapper;

    /** Loads full player snapshots for write responses. */
    private final PlayerSnapshotService playerSnapshotService;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerSettingsMapper converts between domain models and DTOs
     * @param playerSnapshotService loads full player snapshots after writes
     */
    public PlayerSettingsService(PlayerStateRepository playerStateRepository,
                                 PlayerSettingsMapper playerSettingsMapper,
                                 PlayerSnapshotService playerSnapshotService) {
        this.playerStateRepository = playerStateRepository;
        this.playerSettingsMapper = playerSettingsMapper;
        this.playerSnapshotService = playerSnapshotService;
    }

    /**
     * Retrieves settings for the given player.
     *
     * @param playerId the internal player id
     * @return the settings slice response DTO
     * @throws PlayerNotFoundException if no settings exist for the given id
     */
    public GetSettingsResponse getSettings(String playerId) {
        PlayerSettings settings = playerStateRepository.getSettings(playerId).join();

        if (settings == null) {
            throw new PlayerNotFoundException(playerId);
        }

        logger.debug("Retrieved player settings [playerId={}]", playerId);
        return new GetSettingsResponse(playerSettingsMapper.toSnapshot(settings));
    }

    /**
     * Applies a partial update to player settings with optimistic locking.
     *
     * @param playerId the target player
     * @param request  contains optional field overrides and the expected version
     * @return the full player snapshot after the update
     * @throws PlayerNotFoundException if no settings exist for the given id
     * @throws StaleVersionException   if the expected version does not match the current version
     */
    public UpdatePlayerSettingsResponse updateSettings(String playerId,
                                                       UpdatePlayerSettingsRequest request) {
        PlayerSettings current = playerStateRepository.getSettings(playerId).join();
        if (current == null) {
            throw new PlayerNotFoundException(playerId);
        }

        // Apply only non-null overrides (partial update)
        if (request.notificationsEnabled() != null) {
            current.setNotificationsEnabled(request.notificationsEnabled());
        }
        if (request.preferredLanguage() != null) {
            current.setPreferredLanguage(request.preferredLanguage());
        }
        if (request.profileVisibility() != null) {
            current.setProfileVisibility(request.profileVisibility());
        }
        current.setVersion(request.expectedVersion());

        logger.debug("Updating player settings [playerId={}, expectedVersion={}]",
                playerId, request.expectedVersion());

        try {
            playerStateRepository.updateSettings(current).join();
            logger.debug("Player settings updated [playerId={}]", playerId);
            var snapshot = playerSnapshotService.load(playerId);
            return new UpdatePlayerSettingsResponse(
                    snapshot.playerId(),
                    snapshot.profile(),
                    snapshot.wallet(),
                    snapshot.settings());
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof ConditionalCheckFailedException) {
                throw new StaleVersionException(playerId, request.expectedVersion());
            }
            throw ex;
        }
    }
}
