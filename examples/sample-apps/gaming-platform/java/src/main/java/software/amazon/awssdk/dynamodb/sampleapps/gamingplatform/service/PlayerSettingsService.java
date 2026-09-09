package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.TransactionRetry;
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

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerSettingsMapper converts between domain models and DTOs
     */
    public PlayerSettingsService(PlayerStateRepository playerStateRepository,
                                 PlayerSettingsMapper playerSettingsMapper) {
        this.playerStateRepository = playerStateRepository;
        this.playerSettingsMapper = playerSettingsMapper;
    }

    /**
     * Retrieves settings for the given player.
     *
     * @param playerId the internal player id
     * @return future of the settings slice response DTO
     * @throws PlayerNotFoundException if no settings exist for the given id
     */
    public CompletableFuture<GetSettingsResponse> getSettings(String playerId) {
        return playerStateRepository.getSettings(playerId).thenApply(settings -> {
            if (settings == null) {
                throw new PlayerNotFoundException(playerId);
            }
            logger.debug("Retrieved player settings [playerId={}]", playerId);
            return new GetSettingsResponse(playerSettingsMapper.toSnapshot(settings));
        });
    }

    /**
     * Applies a partial update to player settings with optimistic locking.
     *
     * @param playerId the target player
     * @param request  contains optional field overrides and the expected version
     * @return future of the updated settings slice after the update
     * @throws PlayerNotFoundException if no settings exist for the given id
     * @throws StaleVersionException   if the expected version does not match the current version
     */
    public CompletableFuture<UpdatePlayerSettingsResponse> updateSettings(String playerId,
                                                                          UpdatePlayerSettingsRequest request) {
        return playerStateRepository.getSettings(playerId).thenCompose(current -> {
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

            return playerStateRepository.updateSettings(current)
                    .<PlayerSettings>exceptionallyCompose(error -> {
                        Throwable cause = TransactionRetry.unwrap(error);
                        if (cause instanceof ConditionalCheckFailedException) {
                            return CompletableFuture.failedFuture(
                                    new StaleVersionException(playerId, request.expectedVersion()));
                        }
                        return CompletableFuture.failedFuture(cause);
                    })
                    .thenApply(updated -> {
                        logger.debug("Player settings updated [playerId={}]", playerId);
                        return new UpdatePlayerSettingsResponse(
                                playerId, playerSettingsMapper.toSnapshot(updated));
                    });
        });
    }
}
