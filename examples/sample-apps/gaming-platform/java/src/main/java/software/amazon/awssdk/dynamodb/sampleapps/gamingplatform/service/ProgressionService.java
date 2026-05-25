package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Applies experience deltas to a player profile with optimistic locking.
 *
 * <p>Reads the current profile to compute the new level before issuing a conditional
 * update. If the version has moved since the caller read the profile, a
 * {@link StaleVersionException} is thrown so the client can retry with a fresh read.
 *
 * <p>When a level-up is detected, a soft-currency bonus of {@link #LEVEL_UP_BONUS} is
 * automatically credited via {@link CurrencyRewardService} so players are rewarded for progression.
 *
 * @see PlayerStateRepository#updateProgression
 */
@Service
public class ProgressionService {

    private static final Logger logger = LoggerFactory.getLogger(ProgressionService.class);

    /** Maximum level returned by {@link #computeLevel(long)}. */
    public static final int MAX_LEVEL = 100;

    /** Experience points required per level tier for level calculation. */
    public static final long XP_PER_LEVEL = 1000L;

    /**
     * Soft currency credited to the player wallet on every level-up.
     *
     * <p>Example: crossing from level 4 to level 5 awards {@value #LEVEL_UP_BONUS} soft currency,
     * which is processed as a {@code CURRENCY_GRANT} event via {@link CurrencyRewardService}.
     */
    public static final long LEVEL_UP_BONUS = 250L;

    /** Conditional writes against PlayerState. */
    private final PlayerStateRepository playerStateRepository;

    /** Currency reward service for level-up bonuses. */
    private final CurrencyRewardService currencyRewardService;

    /** Loads full player snapshots for write responses. */
    private final PlayerSnapshotService playerSnapshotService;

    /**
     * Constructs the service with required collaborators.
     *
     * @param playerStateRepository player-state data access
     * @param currencyRewardService grants level-up currency bonuses
     * @param playerSnapshotService loads full player snapshots after writes
     */
    public ProgressionService(PlayerStateRepository playerStateRepository,
                               CurrencyRewardService currencyRewardService,
                               PlayerSnapshotService playerSnapshotService) {
        this.playerStateRepository = playerStateRepository;
        this.currencyRewardService = currencyRewardService;
        this.playerSnapshotService = playerSnapshotService;
    }

    /**
     * Applies an XP delta to the player, recomputing the level if the XP threshold is crossed.
     *
     * <p>When the updated level is higher than the current level a soft-currency bonus of
     * {@link #LEVEL_UP_BONUS} is automatically credited via {@link CurrencyRewardService}.
     * The idempotency key for the bonus is {@code "levelup-<playerId>-<newLevel>"} so retrying
     * the same XP update never double-awards the bonus.
     *
     * @param playerId the target player
     * @param request  contains xpDelta, reason, and expectedVersion
     * @return the full player snapshot wrapped in a response DTO
     * @throws PlayerNotFoundException if no profile exists for the given id
     * @throws StaleVersionException   if the expectedVersion does not match the current version
     */
    public ProgressionUpdateResponse updateProgression(String playerId,
                                                        ProgressionUpdateRequest request) {
        PlayerProfile current = playerStateRepository.getPlayer(playerId).join();
        if (current == null) {
            throw new PlayerNotFoundException(playerId);
        }

        int newLevel = computeLevel(current.getTotalExperience() + request.xpDelta());
        boolean leveledUp = newLevel > current.getCurrentLevel();

        logger.debug("Updating player progression [playerId={}, xpDelta={}, newLevel={}, leveledUp={}]",
                playerId, request.xpDelta(), newLevel, leveledUp);

        try {
            PlayerProfile updated = playerStateRepository.updateProgression(
                    playerId,
                    current.getTotalExperience() + request.xpDelta(),
                    newLevel,
                    request.expectedVersion()
            ).join();

            logger.info("Progression updated [playerId={}, newLevel={}, profileVersion={}]",
                    playerId, newLevel, updated.getVersion());

            if (leveledUp) {
                String bonusRequestId = "levelup-" + playerId + "-" + newLevel;
                try {
                    currencyRewardService.grantCurrency(
                            playerId, LEVEL_UP_BONUS, CurrencyEarnReason.LEVEL_UP_BONUS, bonusRequestId);
                    logger.info("Level-up bonus granted [playerId={}, newLevel={}, bonusAmount={}]",
                            playerId, newLevel, LEVEL_UP_BONUS);
                } catch (Exception ex) {
                    // Best-effort level-up bonus. Progression write already committed, so log only.
                    logger.warn("Level-up bonus grant failed after progression update [playerId={}, newLevel={}, bonusAmount={}, detail={}]",
                            playerId, newLevel, LEVEL_UP_BONUS, ex.getMessage());
                }
            }

            var snapshot = playerSnapshotService.load(playerId);
            return new ProgressionUpdateResponse(
                    snapshot.playerId(),
                    snapshot.profile(),
                    snapshot.wallet(),
                    snapshot.settings(),
                    null);
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof ConditionalCheckFailedException) {
                throw new StaleVersionException(playerId, request.expectedVersion());
            }
            throw ex;
        }
    }

    /**
     * Computes the player level from cumulative XP.
     *
     * <p>Each level requires {@value #XP_PER_LEVEL} XP. The minimum level is 1 and
     * the maximum is {@value #MAX_LEVEL}.
     *
     * @param totalXp cumulative experience points. Negative values are clamped for level calculation.
     * @return level in the inclusive range {@code [1, MAX_LEVEL]}
     */
    protected int computeLevel(long totalXp) {
        if (totalXp < 0) {
            return 1;
        }
        int level = (int) (totalXp / XP_PER_LEVEL) + 1;
        return Math.min(level, MAX_LEVEL);
    }
}
