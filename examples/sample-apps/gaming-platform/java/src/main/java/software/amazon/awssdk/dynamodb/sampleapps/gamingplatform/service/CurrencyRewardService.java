package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletEarnResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.WalletSnapshot;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.WalletTransactItemOrder;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.TransactionRetry;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Orchestrates all soft-currency earn paths (gameplay rewards, daily login, admin grants).
 *
 * <p>Every credit runs through {@link PlayerStateRepository#earnCurrencyTransaction}, which
 * atomically increments {@code currencyBalance} and bumps the wallet {@code version} under an
 * optimistic-lock guard, and writes a {@link GameEventType#CURRENCY_GRANT}
 * audit event. The caller-supplied {@code clientRequestId} is embedded in the event sort key so
 * duplicate calls return {@code IDEMPOTENT_REPLAY} without crediting the player twice.
 *
     * <p><strong>Transaction index contract:</strong> the two transact items are ordered by
     * {@link WalletTransactItemOrder}.
     * <ul>
     *   <li>{@link WalletTransactItemOrder#WALLET}: wallet credit guarded by
     *       {@code attribute_exists(PK) AND version = :expectedVersion}. A conditional failure here
     *       means the wallet version moved since the pre-read (stale version).</li>
     *   <li>{@link WalletTransactItemOrder#EVENT}: event {@code PUT} guarded by
     *       {@code attribute_not_exists(PK)}. Fails when the same {@code clientRequestId} has been
     *       processed before (idempotent replay).</li>
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

    /**
     * Constructs the service.
     *
     * @param playerStateRepository player-state and event data access
     * @param gameEventMapper builds earn events from request data
     */
    public CurrencyRewardService(PlayerStateRepository playerStateRepository,
                                 GameEventMapper gameEventMapper) {
        this.playerStateRepository = playerStateRepository;
        this.gameEventMapper = gameEventMapper;
    }

    /**
     * Credits soft currency to a player wallet.
     *
     * <p>Validates that the player wallet exists, then executes the atomic earn transaction.
     * Idempotent: supplying the same {@code clientRequestId} returns {@code IDEMPOTENT_REPLAY}
     * with the current wallet state and the original event id.
     *
     * <p>A {@code TransactionConflict} cancellation (concurrent transaction on the same wallet) is
     * retried by {@link TransactionRetry}, re-reading the wallet between attempts. An idempotent
     * replay or a missing wallet is not a conflict and is handled without retry.
     *
     * @param playerId the player to credit
     * @param request  amount, reason, and idempotency key
     * @return future of the outcome with status, wallet state, and event id
     * @throws WalletNotFoundException if no wallet exists for the given player id
     */
    public CompletableFuture<WalletEarnResponse> grantCurrency(String playerId, WalletEarnRequest request) {
        return TransactionRetry.runWithConflictRetryAsync(() -> attemptGrantCurrency(playerId, request));
    }

    /**
     * Runs a single earn attempt: reads the wallet, builds the event, and executes the transact.
     *
     * <p>A {@code TransactionConflict} cancellation is re-thrown as a failed future so
     * {@link TransactionRetry#runWithConflictRetryAsync} re-attempts. Other cancellations (idempotent
     * replay, missing wallet) are resolved here without retry.
     *
     * @param playerId the player to credit
     * @param request  amount, reason, and idempotency key
     * @return future of the outcome with status, wallet state, and event id
     */
    private CompletableFuture<WalletEarnResponse> attemptGrantCurrency(String playerId, WalletEarnRequest request) {
        return playerStateRepository.getWallet(playerId).thenCompose(wallet -> {
            if (wallet == null) {
                return CompletableFuture.<WalletEarnResponse>failedFuture(new WalletNotFoundException(playerId));
            }

            GameEvent rewardEvent = gameEventMapper.toCurrencyGrantEvent(
                    playerId, request.amount(), request.reason(), request.clientRequestId());

            logger.debug("Granting currency [playerId={}, amount={}, reason={}, eventId={}]",
                    playerId, request.amount(), request.reason(), rewardEvent.getEventId());

            // Atomic wallet credit then event PUT with uniqueness guard, ordered by WalletTransactItemOrder
            return playerStateRepository.earnCurrencyTransaction(wallet, request.amount(), rewardEvent)
                    .thenApply(ignored -> {
                        logger.debug("Currency granted [status=COMPLETED, playerId={}, amount={}, reason={}, eventId={}]",
                                playerId, request.amount(), request.reason(), rewardEvent.getEventId());
                        return completedResponse(playerId, wallet, request.amount(), rewardEvent.getEventId());
                    })
                    .exceptionallyCompose(error -> {
                        Throwable cause = TransactionRetry.unwrap(error);
                        if (cause instanceof TransactionCanceledException transactionCanceledException) {
                            if (TransactionRetry.isTransactionConflict(transactionCanceledException)) {
                                // Serializable conflict: propagate so the retry wrapper re-attempts.
                                return CompletableFuture.<WalletEarnResponse>failedFuture(transactionCanceledException);
                            }
                            return handleTransactionCancellation(transactionCanceledException, playerId, wallet, rewardEvent);
                        }
                        return CompletableFuture.<WalletEarnResponse>failedFuture(cause);
                    });
        });
    }

    /**
     * Convenience method for game-loop earn calls (e.g. from {@link ProgressionService} on level-up).
     *
     * @param playerId        the player to credit
     * @param amount          positive currency amount
     * @param reason          origin of the credit
     * @param clientRequestId idempotency key
     * @return future of the earn outcome
     */
    public CompletableFuture<WalletEarnResponse> grantCurrency(String playerId, long amount,
                                                               CurrencyEarnReason reason, String clientRequestId) {
        return grantCurrency(playerId, new WalletEarnRequest(amount, reason, clientRequestId));
    }

    /**
     * Inspects the transaction cancellation to distinguish between an idempotent replay
     * ({@link WalletTransactItemOrder#EVENT}, duplicate event key) and a missing wallet
     * ({@link WalletTransactItemOrder#WALLET}).
     *
     * @param transactionCanceledException the transaction cancellation from DynamoDB
     * @param playerId    the player whose earn was attempted
     * @param rewardEvent the event that was part of the transaction
     * @return future of an idempotent replay response when the event key already existed, otherwise a
     *     failed future carrying {@link WalletNotFoundException} or the original cancellation
     */
    private CompletableFuture<WalletEarnResponse> handleTransactionCancellation(
            TransactionCanceledException transactionCanceledException,
            String playerId,
            PlayerWallet wallet,
            GameEvent rewardEvent) {
        List<CancellationReason> reasons = transactionCanceledException.cancellationReasons();

        // Event PUT failed. A duplicate clientRequestId means idempotent replay.
        int eventIndex = WalletTransactItemOrder.EVENT.index();
        if (reasons.size() > eventIndex && isConditionalCheckFailed(reasons.get(eventIndex))) {
            logger.debug("Currency grant idempotent replay detected [status=IDEMPOTENT_REPLAY, playerId={}, eventId={}]",
                    playerId, rewardEvent.getEventId());
            return CompletableFuture.completedFuture(replayResponse(playerId, wallet, rewardEvent.getEventId()));
        }

        // Wallet credit failed the optimistic-lock guard (version moved since the pre-read). The
        // wallet existed at pre-read, so a conditional failure here is a stale version, not a missing
        // wallet. Mirrors the purchase debit handling.
        int walletIndex = WalletTransactItemOrder.WALLET.index();
        if (reasons.size() > walletIndex && isConditionalCheckFailed(reasons.get(walletIndex))) {
            return CompletableFuture.failedFuture(new StaleVersionException(playerId, wallet.getVersion()));
        }

        return CompletableFuture.failedFuture(transactionCanceledException);
    }

    /**
     * Builds the {@link #STATUS_COMPLETED} response from the pre-credit wallet.
     *
     * <p>The earn increments {@code currencyBalance} by the credited amount and bumps the wallet
     * {@code version} by one under an optimistic-lock guard, so the new balance is the pre-read
     * balance plus the amount and the new version is the pre-read version plus one. Computing the
     * response locally avoids any extra read after the write and is not subject to read-after-write
     * staleness.
     *
     * @param playerId        the credited player
     * @param preCreditWallet wallet read before the credit
     * @param amount          positive currency amount just credited
     * @param eventId         the reward event identifier
     * @return populated response DTO
     */
    private WalletEarnResponse completedResponse(String playerId, PlayerWallet preCreditWallet,
                                                 long amount, String eventId) {
        WalletSnapshot wallet = new WalletSnapshot(
                preCreditWallet.getCurrencyBalance() + amount, preCreditWallet.getVersion() + 1);
        return new WalletEarnResponse(playerId, wallet, STATUS_COMPLETED, eventId);
    }

    /**
     * Builds the {@link #STATUS_IDEMPOTENT_REPLAY} response from the wallet read at the start of this
     * attempt, which already reflects the balance credited by the original request.
     *
     * @param playerId      the player whose earn was replayed
     * @param currentWallet wallet read at the start of this attempt
     * @param eventId       the original reward event identifier
     * @return populated response DTO
     */
    private WalletEarnResponse replayResponse(String playerId, PlayerWallet currentWallet, String eventId) {
        WalletSnapshot wallet = new WalletSnapshot(
                currentWallet.getCurrencyBalance(), currentWallet.getVersion());
        return new WalletEarnResponse(playerId, wallet, STATUS_IDEMPOTENT_REPLAY, eventId);
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
