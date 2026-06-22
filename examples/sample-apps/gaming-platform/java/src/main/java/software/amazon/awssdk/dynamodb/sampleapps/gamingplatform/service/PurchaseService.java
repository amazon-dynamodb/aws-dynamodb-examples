package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.List;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InsufficientFundsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerWallet;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.WalletTransactItemOrder;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.TransactionRetry;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Executes atomic in-game purchases using a DynamoDB cross-table transaction.
 *
 * <p>The transaction deducts soft currency from the player wallet on the PlayerState
 * table and writes a purchase event to the GameEvents table in a single ACID operation.
 * A client-supplied idempotency key ({@code clientRequestId}) prevents double-spend on
 * retries: if the event already exists the service detects the duplicate and returns
 * {@code IDEMPOTENT_REPLAY} without charging the player again.
 *
 * @see PlayerStateRepository#purchaseTransaction
 */
@Service
public class PurchaseService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseService.class);

    /** Response status when currency is debited and the purchase event is new. */
    static final String STATUS_COMPLETED = "COMPLETED";

    /** Response status when the transactional event write was a duplicate key (safe replay). */
    static final String STATUS_IDEMPOTENT_REPLAY = "IDEMPOTENT_REPLAY";

    /** Atomic player-state and event writes. */
    private final PlayerStateRepository playerStateRepository;

    /** Builds deterministic purchase events for the transaction. */
    private final GameEventMapper gameEventMapper;

    /** Loads full player snapshots for write responses. */
    private final PlayerSnapshotService playerSnapshotService;

    /**
     * Constructs the service.
     *
     * @param playerStateRepository player-state and transaction data access
     * @param gameEventMapper       builds purchase events from request DTOs
     * @param playerSnapshotService loads full player snapshots after writes
     */
    public PurchaseService(PlayerStateRepository playerStateRepository,
                           GameEventMapper gameEventMapper,
                           PlayerSnapshotService playerSnapshotService) {
        this.playerStateRepository = playerStateRepository;
        this.gameEventMapper = gameEventMapper;
        this.playerSnapshotService = playerSnapshotService;
    }

    /**
     * Validates the player's balance and executes an atomic purchase transaction.
     *
     * <p>A {@code TransactionConflict} cancellation (concurrent transaction on the same wallet) is
     * retried by {@link TransactionRetry}, re-reading the wallet so each attempt rebuilds the transact
     * with a fresh optimistic-lock version. Conditional failures (stale version, insufficient funds,
     * idempotent replay) are not conflicts and are handled without retry.
     *
     * @param playerId the purchasing player
     * @param request  item id, cost, and idempotency key
     * @return outcome with status, full player snapshot, and event id
     * @throws PlayerNotFoundException    if no profile exists for the given id
     * @throws WalletNotFoundException    if the profile exists but the wallet row is missing
     * @throws InsufficientFundsException if the player's balance is below the cost (fail-fast)
     * @throws StaleVersionException      if the wallet version moved during the transaction
     */
    public PurchaseResponse executePurchase(String playerId, PurchaseRequest request) {
        return TransactionRetry.runWithConflictRetry(() -> attemptPurchase(playerId, request));
    }

    /**
     * Runs a single purchase attempt: reads the wallet, builds the event, and executes the transact.
     *
     * @param playerId the purchasing player
     * @param request  item id, cost, and idempotency key
     * @return outcome with status, full player snapshot, and event id
     */
    private PurchaseResponse attemptPurchase(String playerId, PurchaseRequest request) {
        PlayerProfile profile = playerStateRepository.getPlayer(playerId).join();
        if (profile == null) {
            throw new PlayerNotFoundException(playerId);
        }

        PlayerWallet wallet = playerStateRepository.getWallet(playerId).join();
        if (wallet == null) {
            throw new WalletNotFoundException(playerId);
        }

        // Fail-fast balance check before the transaction round trip
        if (wallet.getCurrencyBalance() < request.softCurrencyCost()) {
            throw new InsufficientFundsException(
                    playerId, request.softCurrencyCost(), wallet.getCurrencyBalance());
        }

        GameEvent purchaseEvent = gameEventMapper.toPurchaseEvent(playerId, request);

        logger.debug("Executing purchase transaction [playerId={}, itemId={}, cost={}, eventId={}]",
                playerId, request.itemId(), request.softCurrencyCost(), purchaseEvent.getEventId());

        // Atomic wallet debit then event PUT with uniqueness guard, ordered by WalletTransactItemOrder
        try {
            playerStateRepository.purchaseTransaction(
                    wallet, request.softCurrencyCost(), request.itemId(), purchaseEvent
            ).join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof TransactionCanceledException transactionCanceledException) {
                return handleTransactionCancellation(transactionCanceledException, playerId, wallet, purchaseEvent);
            }
            throw ex;
        }

        logger.debug("Purchase completed [status=COMPLETED, playerId={}, itemId={}, eventId={}]",
                playerId, request.itemId(), purchaseEvent.getEventId());
        return toPurchaseResponse(STATUS_COMPLETED, playerId, purchaseEvent.getEventId());
    }

    /**
     * Inspects transaction cancellation reasons to decide between an idempotent replay
     * (duplicate event key) and a real conflict (stale version or insufficient funds).
     *
     * @param transactionCanceledException the transaction cancellation from DynamoDB
     * @param playerId      the purchasing player
     * @param wallet        the wallet snapshot read before the transaction (carries expected version)
     * @param purchaseEvent the event that was part of the transaction
     * @return an idempotent replay response when the event key already existed
     * @throws StaleVersionException if the wallet condition check failed ({@link WalletTransactItemOrder#WALLET})
     * @throws TransactionCanceledException if neither known index matches
     */
    private PurchaseResponse handleTransactionCancellation(TransactionCanceledException transactionCanceledException,
                                                           String playerId,
                                                           PlayerWallet wallet,
                                                           GameEvent purchaseEvent) {
        List<CancellationReason> reasons = transactionCanceledException.cancellationReasons();

        // The event Put carries attribute_not_exists(PK), so a failure here is a duplicate purchase id
        int eventIndex = WalletTransactItemOrder.EVENT.index();
        if (reasons.size() > eventIndex && isConditionalCheckFailed(reasons.get(eventIndex))) {
            logger.debug("Purchase idempotent replay detected [status=IDEMPOTENT_REPLAY, playerId={}, eventId={}]",
                    playerId, purchaseEvent.getEventId());
            return toPurchaseResponse(STATUS_IDEMPOTENT_REPLAY, playerId, purchaseEvent.getEventId());
        }

        // The wallet Update could fail on stale version or insufficient funds
        int walletIndex = WalletTransactItemOrder.WALLET.index();
        if (reasons.size() > walletIndex && isConditionalCheckFailed(reasons.get(walletIndex))) {
            throw new StaleVersionException(playerId, wallet.getVersion());
        }

        throw transactionCanceledException;
    }

    /**
     * Loads the full player snapshot and wraps it in a purchase response.
     *
     * @param status          outcome label
     * @param playerId        the purchasing player
     * @param purchaseEventId the purchase event identifier
     * @return purchase response with full snapshot
     */
    private PurchaseResponse toPurchaseResponse(String status, String playerId, String purchaseEventId) {
        var snapshot = playerSnapshotService.load(playerId);
        return new PurchaseResponse(
                snapshot.playerId(),
                snapshot.profile(),
                snapshot.wallet(),
                snapshot.settings(),
                status,
                purchaseEventId);
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
