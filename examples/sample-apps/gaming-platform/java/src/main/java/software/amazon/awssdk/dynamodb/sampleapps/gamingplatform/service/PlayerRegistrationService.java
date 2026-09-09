package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerAlreadyExistsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerSettingsMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.PlayerWalletMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerSettings;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.TransactionRetry;
import software.amazon.awssdk.enhanced.dynamodb.extensions.VersionedRecordExtension;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Handles player registration with idempotent retry semantics.
 *
 * <p>Uses a {@code TransactWriteItems} to atomically create a PROFILE, a default
 * SETTINGS, and a default WALLET item under the same partition. When the transaction
 * fails because items already exist, the service reads back the existing profile and
 * decides whether the request is an idempotent replay (same platform + platformUserId)
 * or a genuine conflict that must be rejected.
 */
@Service
public class PlayerRegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerRegistrationService.class);

    /** PlayerState persistence implementation selected by {@code dynamodb.client-type}. */
    private final PlayerStateRepository playerStateRepository;

    /** Maps registration requests and profiles to API types. */
    private final PlayerMapper playerMapper;

    /** Creates default settings for new players. */
    private final PlayerSettingsMapper settingsMapper;

    /** Creates default wallet for new players. */
    private final PlayerWalletMapper walletMapper;

    /** Loads full player snapshots for write responses. */
    private final PlayerSnapshotService playerSnapshotService;

    /**
     * Creates the service.
     *
     * @param playerStateRepository the active {@link PlayerStateRepository} implementation
     * @param playerMapper converts between DTOs and domain models
     * @param settingsMapper  creates default settings for new registrations
     * @param walletMapper    creates default wallet for new registrations
     * @param playerSnapshotService loads full player snapshots after writes
     */
    public PlayerRegistrationService(PlayerStateRepository playerStateRepository,
                                     PlayerMapper playerMapper,
                                     PlayerSettingsMapper settingsMapper,
                                     PlayerWalletMapper walletMapper,
                                     PlayerSnapshotService playerSnapshotService) {
        this.playerStateRepository = playerStateRepository;
        this.playerMapper = playerMapper;
        this.settingsMapper = settingsMapper;
        this.walletMapper = walletMapper;
        this.playerSnapshotService = playerSnapshotService;
    }

    /**
     * Registers a new player or returns the existing profile for an idempotent replay.
     *
     * @implNote Uses {@code TransactWriteItems} to create PROFILE, SETTINGS, and WALLET items
     *           atomically. After a successful create, reloads the full snapshot so the response
     *           reflects persisted attributes (for example {@code version} written by the
     *           enhanced client's {@link VersionedRecordExtension}). A {@code TransactionConflict}
     *           cancellation (concurrent transaction on the same partition) is retried by
     *           {@link TransactionRetry}. A conditional failure (items already exist) is handled as an
     *           idempotent replay or a genuine conflict and is not retried.
     *
     * @param request the registration request
     * @return future of the response containing the player id, full snapshot, and whether the account is new
     * @throws PlayerAlreadyExistsException if the player id is taken but the platform identity differs
     * @throws WalletNotFoundException      if the profile exists but the wallet row is missing after create
     *                                    or on idempotent replay (partial or legacy data)
     */
    public CompletableFuture<RegisterPlayerResponse> registerPlayer(RegisterPlayerRequest request) {
        return TransactionRetry.runWithConflictRetryAsync(() -> attemptRegister(request));
    }

    /**
     * Runs a single registration attempt: builds the default items and executes the create transact.
     *
     * @param request the registration request
     * @return future of the response containing the player id, full snapshot, and whether the account is new
     */
    private CompletableFuture<RegisterPlayerResponse> attemptRegister(RegisterPlayerRequest request) {
        PlayerProfile profile = playerMapper.toProfile(request);
        PlayerSettings defaultSettings = settingsMapper.defaultSettings(profile.getPlayerId());
        PlayerWallet defaultWallet = walletMapper.defaultWallet(profile.getPlayerId());

        return playerStateRepository.createPlayerWithSettingsAndWallet(profile, defaultSettings, defaultWallet)
                .thenCompose(ignored -> {
                    logger.debug("Player registered [status=CREATED, playerId={}, platform={}]",
                            profile.getPlayerId(), request.platform());
                    return toRegisterResponse(profile.getPlayerId(), true);
                })
                .exceptionallyCompose(error -> {
                    Throwable cause = TransactionRetry.unwrap(error);
                    // TransactionCanceledException means at least one conditional put failed.
                    if (cause instanceof TransactionCanceledException tce) {
                        // A serializable conflict is transient: rethrow so the retry wrapper re-attempts.
                        if (TransactionRetry.isTransactionConflict(tce)) {
                            return CompletableFuture.<RegisterPlayerResponse>failedFuture(tce);
                        }
                        return handleExistingPlayer(profile.getPlayerId(), request);
                    }
                    return CompletableFuture.<RegisterPlayerResponse>failedFuture(cause);
                });
    }

    /**
     * Reads back the existing profile and decides whether the duplicate write was an idempotent
     * replay or a genuine conflict.
     *
     * @param playerId the player id that triggered the transaction conflict
     * @param request  the original registration request for identity comparison
     * @return future of the existing profile wrapped as an idempotent replay response
     * @throws PlayerAlreadyExistsException if the stored platform identity differs from the request
     * @throws WalletNotFoundException      if the profile exists but the wallet row is missing
     */
    private CompletableFuture<RegisterPlayerResponse> handleExistingPlayer(String playerId,
                                                                           RegisterPlayerRequest request) {
        return playerStateRepository.getPlayer(playerId).thenCompose(existing -> {
            // Same platform identity means the client retried registration with the same keys.
            if (existing != null
                    && existing.getPlatform().equals(request.platform())
                    && existing.getPlatformUserId().equals(request.platformUserId())) {
                logger.debug("Player registration idempotent replay [status=IDEMPOTENT_REPLAY, playerId={}]",
                        playerId);
                return toRegisterResponse(playerId, false);
            }

            // Profile exists under the same id but with different platform identity.
            return CompletableFuture.<RegisterPlayerResponse>failedFuture(new PlayerAlreadyExistsException(playerId));
        });
    }

    /**
     * Loads the full player snapshot and wraps it in a registration response.
     *
     * @param playerId the player id
     * @param created  whether the account was newly created
     * @return future of the registration response with full snapshot
     */
    private CompletableFuture<RegisterPlayerResponse> toRegisterResponse(String playerId, boolean created) {
        return playerSnapshotService.load(playerId).thenApply(snapshot -> new RegisterPlayerResponse(
                snapshot.playerId(),
                snapshot.profile(),
                snapshot.wallet(),
                snapshot.settings(),
                created));
    }
}
