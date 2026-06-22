package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.EventsPageResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GameEventDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventPage;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util.PaginationHelper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Records game events with TTL and queries event history.
 *
 * <p>Before writing an event the service verifies that the player exists, then delegates
 * to the {@link GameEventRepository} for an unconditional {@code PutItem}. Each event
 * carries a TTL attribute computed from {@code dynamodb.game-events-ttl-seconds} so that
 * DynamoDB automatically expires old events.
 *
 * @see GameEventRepository#appendEvent
 */
@Service
public class GameEventService {

    private static final Logger logger = LoggerFactory.getLogger(GameEventService.class);

    /** Minimum page size for event history. */
    private static final int MIN_PAGE_SIZE = 1;

    /** Maximum page size for event history. */
    private static final int MAX_PAGE_SIZE = 50;

    /** GameEvents table access. */
    private final GameEventRepository gameEventRepository;

    /** Existence checks against PlayerState. */
    private final PlayerStateRepository playerStateRepository;

    /** Builds domain events from API requests. */
    private final GameEventMapper gameEventMapper;

    /**
     * Constructs the service.
     *
     * @param gameEventRepository   event data access
     * @param playerStateRepository player existence checks
     * @param gameEventMapper       builds events from request DTOs
     */
    public GameEventService(GameEventRepository gameEventRepository,
                            PlayerStateRepository playerStateRepository,
                            GameEventMapper gameEventMapper) {
        this.gameEventRepository = gameEventRepository;
        this.playerStateRepository = playerStateRepository;
        this.gameEventMapper = gameEventMapper;
    }

    /**
     * Records a game event for the given player.
     *
     * <p>Verifies that the player exists, builds a {@link GameEvent} from the request
     * (including TTL), and persists it via an unconditional {@code PutItem}.
     *
     * @param playerId the player who triggered the event
     * @param request  event type and event-specific attributes
     * @return the event id and recorded timestamp
     * @throws PlayerNotFoundException if no profile exists for the given player id
     */
    public RecordEventResponse recordEvent(String playerId, RecordEventRequest request) {
        var profile = playerStateRepository.getPlayer(playerId).join();
        if (profile == null) {
            throw new PlayerNotFoundException(playerId);
        }

        GameEvent event = gameEventMapper.toGameEvent(playerId, request);

        logger.debug("Recording game event [eventId={}, eventType={}, playerId={}]",
                event.getEventId(), request.eventType(), playerId);

        gameEventRepository.appendEvent(event).join();

        logger.debug("Game event recorded [eventId={}, playerId={}, eventType={}]",
                event.getEventId(), playerId, request.eventType());

        return gameEventMapper.toRecordEventResponse(event);
    }

    /**
     * Retrieves a paginated history of game events for a player.
     *
     * <p>When {@code scanIndexForward} is omitted or {@code false}, results are newest first
     * (DynamoDB {@code ScanIndexForward=false}). When {@code true}, oldest first.
     *
     * <p>Validates that the player exists, clamps the requested page size to [{@value MIN_PAGE_SIZE},
     * {@value MAX_PAGE_SIZE}], decodes an optional opaque {@code nextToken} into a DynamoDB
     * {@code ExclusiveStartKey}, and delegates to the repository. The resulting
     * {@code LastEvaluatedKey} is re-encoded into the response {@code nextToken} for the caller.
     *
     * <p>The decoded token is bound to {@code USER#<playerId>}. A token minted for a different
     * player is rejected as {@link InvalidPaginationTokenException} (HTTP 400) before any DynamoDB
     * call, rather than letting DynamoDB reject the mismatched start key as a server error.
     *
     * @param playerId          the player whose events to query
     * @param limit             maximum number of events per page (clamped to [{@value MIN_PAGE_SIZE}, {@value MAX_PAGE_SIZE}])
     * @param scanIndexForward  optional. {@code true} for ascending Query order per DynamoDB
     * @param nextToken         opaque pagination token, or {@code null} or blank for the first page
     * @return a page of {@code events} with an optional {@code nextToken}
     * @throws PlayerNotFoundException        if no profile exists for the given player id
     * @throws InvalidPaginationTokenException if {@code nextToken} is malformed or bound to a different player
     */
    public EventsPageResponse getEvents(String playerId, int limit, Boolean scanIndexForward, String nextToken) {
        var profile = playerStateRepository.getPlayer(playerId).join();
        if (profile == null) {
            throw new PlayerNotFoundException(playerId);
        }

        int clampedLimit = Math.max(MIN_PAGE_SIZE, Math.min(limit, MAX_PAGE_SIZE));
        boolean forward = effectiveScanIndexForward(scanIndexForward);
        String expectedPartitionKey = GameEvent.PK_PREFIX + playerId;
        Map<String, AttributeValue> exclusiveStartKey =
                PaginationHelper.decodePaginationToken(nextToken, expectedPartitionKey);

        logger.debug("Querying game event history [playerId={}, limit={}, scanIndexForward={}, hasNextToken={}]",
                playerId, clampedLimit, forward, exclusiveStartKey != null);

        GameEventPage page = gameEventRepository
                .queryEventsByPlayer(playerId, clampedLimit, forward, exclusiveStartKey)
                .join();

        List<GameEventDto> events = page.events().stream()
                .map(gameEventMapper::toEventDto)
                .toList();

        String encodedNextToken = PaginationHelper.encodePaginationToken(page.lastEvaluatedKey());

        return new EventsPageResponse(events, encodedNextToken);
    }

    /**
     * @param scanIndexForward raw query parameter (may be {@code null})
     * @return {@code true} only when the client sends {@code true}. Otherwise {@code false} (newest first)
     */
    private static boolean effectiveScanIndexForward(Boolean scanIndexForward) {
        return Boolean.TRUE.equals(scanIndexForward);
    }
}
