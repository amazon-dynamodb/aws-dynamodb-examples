package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Orchestrates all soft-currency earn paths (gameplay rewards, daily login, admin grants).
 *
 * <p>Every credit runs through {@link PlayerStateRepository#earnCurrencyTransaction}, which
 * atomically increments {@code currencyBalance} via a DynamoDB {@code ADD} expression and writes
 * a {@link GameEventType#CURRENCY_GRANT}
 * audit event. The caller-supplied {@code clientRequestId} is embedded in the event sort key so
 * duplicate calls return {@code IDEMPOTENT_REPLAY} without crediting the player twice.
 *
 * <p><strong>Transaction index contract:</strong>
 * <ul>
 *   <li>Index 0: wallet {@code ADD} guarded by {@code attribute_exists(PK)}. Fails only when the
 *       wallet item does not exist (registration incomplete).</li>
 *   <li>Index 1: event {@code PUT} guarded by {@code attribute_not_exists(PK)}. Fails when the
 *       same {@code clientRequestId} has been processed before (idempotent replay).</li>
 * </ul>
 */
@Service
public class CurrencyRewardService {

    private static final Logger logger = LoggerFactory.getLogger(CurrencyRewardService.class);

    /** Response status when currency is credited and the reward event is new. */
    static final String STATUS_COMPLETED = "COMPLETED";

    /** Response status when the same {@code clientRequestId} was already processed. */
    static final String STATUS_IDEMPOTENT_REPLAY = "IDEMPOTENT_REPLAY";

    /** Player-state and event persistence. */
    private final PlayerStateRepository playerStateRepository;

    /** Builds deterministic {@code CURRENCY_GRANT} events. */
    private final GameEventMapper gameEventMapper;

    /** Loads full player snapshots for write responses. */
    private final PlayerSnapshotService playerSnapshotService;

    /**
     * Constructs the service.
     *
     * @param playerStateRepository player-state and event data access
     * @param gameEventMapper builds earn events from request data
     * @param playerSnapshotService loads full player snapshots after writes
     */
    public CurrencyRewardService(PlayerStateRepository playerStateRepository,
                                 GameEventMapper gameEventMapper,
                                 PlayerSnapshotService playerSnapshotService) {
        this.playerStateRepository = playerStateRepository;
        this.gameEventMapper = gameEventMapper;
        this.playerSnapshotService = playerSnapshotService;
    }

    /**
     * Credits soft currency to a player wallet.
     *
     * <p>Validates that the player wallet exists, then executes the atomic earn transaction.
     * Idempotent: supplying the same {@code clientRequestId} returns {@code IDEMPOTENT_REPLAY}
     * with the current wallet state and the original event id.
     *
     * @param playerId the player to credit
     * @param request  amount, reason, and idempotency key
     * @return outcome with status, full player snapshot, and event id
     * @throws WalletNotFoundException if no wallet exists for the given player id
     */
    public WalletEarnResponse grantCurrency(String playerId, WalletEarnRequest request) {
        PlayerWallet wallet = playerStateRepository.getWallet(playerId).join();
        if (wallet == null) {
            throw new WalletNotFoundException(playerId);
        }

        GameEvent rewardEvent = gameEventMapper.toCurrencyGrantEvent(
                playerId, request.amount(), request.reason(), request.clientRequestId());

        logger.debug("Granting currency [playerId={}, amount={}, reason={}, eventId={}]",
                playerId, request.amount(), request.reason(), rewardEvent.getEventId());

        // Atomic: wallet ADD (index 0) + event PUT with uniqueness guard (index 1)
        try {
            playerStateRepository.earnCurrencyTransaction(playerId, request.amount(), rewardEvent).join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof TransactionCanceledException transactionCanceledException) {
                return handleTransactionCancellation(transactionCanceledException, playerId, rewardEvent);
            }
            throw ex;
        }

        logger.info("Currency granted [status=COMPLETED, playerId={}, amount={}, reason={}, eventId={}]",
                playerId, request.amount(), request.reason(), rewardEvent.getEventId());
        return toEarnResponse(STATUS_COMPLETED, playerId, rewardEvent.getEventId());
    }

    /**
     * Convenience method for game-loop earn calls (e.g. from {@link ProgressionService} on level-up).
     *
     * @param playerId        the player to credit
     * @param amount          positive currency amount
     * @param reason          origin of the credit
     * @param clientRequestId idempotency key
     * @return earn outcome
     */
    public WalletEarnResponse grantCurrency(String playerId, long amount,
                                             CurrencyEarnReason reason, String clientRequestId) {
        return grantCurrency(playerId, new WalletEarnRequest(amount, reason, clientRequestId));
    }

    /**
     * Inspects the transaction cancellation to distinguish between an idempotent replay
     * (index 1, duplicate event key) and a missing wallet (index 0).
     *
     * @param transactionCanceledException the transaction cancellation from DynamoDB
     * @param playerId    the player whose earn was attempted
     * @param rewardEvent the event that was part of the transaction
     * @return an idempotent replay response when the event key already existed
     * @throws WalletNotFoundException if the wallet condition check failed (index 0)
     * @throws TransactionCanceledException if neither known index matches
     */
    private WalletEarnResponse handleTransactionCancellation(TransactionCanceledException transactionCanceledException,
                                                            String playerId,
                                                            GameEvent rewardEvent) {
        List<CancellationReason> reasons = transactionCanceledException.cancellationReasons();

        // Index 1: event PUT failed. Duplicate clientRequestId means idempotent replay.
        if (reasons.size() > 1 && isConditionalCheckFailed(reasons.get(1))) {
            logger.info("Currency grant idempotent replay detected [status=IDEMPOTENT_REPLAY, playerId={}, eventId={}]",
                    playerId, rewardEvent.getEventId());
            return toEarnResponse(STATUS_IDEMPOTENT_REPLAY, playerId, rewardEvent.getEventId());
        }

        // Index 0: wallet ADD failed because the wallet item does not exist.
        if (!reasons.isEmpty() && isConditionalCheckFailed(reasons.get(0))) {
            throw new WalletNotFoundException(playerId);
        }

        throw transactionCanceledException;
    }

    /**
     * Loads the full player snapshot and wraps it in an earn response.
     *
     * @param status  outcome label ({@link #STATUS_COMPLETED} or {@link #STATUS_IDEMPOTENT_REPLAY})
     * @param playerId the credited player
     * @param eventId  the reward event identifier
     * @return populated response DTO
     */
    private WalletEarnResponse toEarnResponse(String status, String playerId, String eventId) {
        var snapshot = playerSnapshotService.load(playerId);
        return new WalletEarnResponse(
                snapshot.playerId(),
                snapshot.profile(),
                snapshot.wallet(),
                snapshot.settings(),
                status,
                eventId);
    }

    /**
     * Checks whether a single cancellation reason indicates a conditional check failure.
     *
     * @param reason cancellation reason from {@link TransactionCanceledException}
     * @return {@code true} when DynamoDB reports {@code ConditionalCheckFailed}
     */
    private static boolean isConditionalCheckFailed(CancellationReason reason) {
        return "ConditionalCheckFailed".equals(reason.code());
    }
}
