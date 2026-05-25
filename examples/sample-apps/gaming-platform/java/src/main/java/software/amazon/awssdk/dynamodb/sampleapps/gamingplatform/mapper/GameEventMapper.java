package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GameEventDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.CurrencyRewardService;

/**
 * Converts between {@link GameEvent} domain objects, REST request DTOs, and REST response DTOs.
 *
 * <p>This mapper handles three categories of game event:
 * <ul>
 *   <li>Generic events recorded via {@link RecordEventRequest} (match results, XP gains)</li>
 *   <li>Purchase events derived from {@link PurchaseRequest}</li>
 *   <li>Currency grant events created by the {@link CurrencyRewardService} earn flow</li>
 * </ul>
 *
 * <p>Purchase and currency-grant events use deterministic UUID v3 event ids derived from the
 * caller-supplied {@code clientRequestId}. This guarantees that transaction retries target the
 * same DynamoDB primary key, enabling idempotent writes via {@code attribute_not_exists}
 * condition checks.
 *
 * <p>A configurable TTL (in seconds) is applied to every persisted event so DynamoDB can
 * expire old rows automatically.
 *
 * @see GameEvent
 * @see RecordEventRequest
 * @see PurchaseRequest
 */
@Component
public class GameEventMapper {

    /** Seconds to add to now when writing {@link GameEvent#setTtl(Long)} from configuration. */
    private final long ttlSeconds;

    /**
     * Constructs the mapper with TTL configuration.
     *
     * @param ttlSeconds value from {@code dynamodb.game-events-ttl-seconds}
     */
    public GameEventMapper(@Value("${dynamodb.game-events-ttl-seconds:7776000}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Builds a game event from a generic record-event request.
     *
     * <p>Flattens the eventAttributes map into top-level attributes on the bean and computes
     * the TTL epoch-seconds value.
     *
     * @param playerId owner of the event
     * @param request  the incoming record-event request
     * @return a fully populated {@link GameEvent} ready for persistence
     */
    public GameEvent toGameEvent(String playerId, RecordEventRequest request) {
        // Random UUID. Generic events do not require deterministic ids.
        String eventId = UUID.randomUUID().toString();
        String recordedAt = Instant.now().toString();

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + playerId);
        // Sort key embeds timestamp first for chronological range queries, then eventId for uniqueness
        event.setSortKey(GameEvent.SK_PREFIX + recordedAt + "#" + eventId);
        event.setEntityType(GameEvent.ENTITY_TYPE);
        event.setEventId(eventId);
        event.setPlayerId(playerId);
        event.setEventType(request.eventType());
        event.setRecordedAt(recordedAt);

        applyEventAttributes(event, request.eventAttributes());

        if (ttlSeconds > 0) {
            event.setTtl(Instant.now().getEpochSecond() + ttlSeconds);
        }

        return event;
    }

    /**
     * Copies recognized fields from {@code eventAttributes} into the game event bean.
     *
     * <p>Only non-null event attribute values that match a known key are applied. Unrecognised
     * keys are silently ignored.
     *
     * @param event   the event being populated
     * @param eventAttributes optional request event attribute map
     */
    private void applyEventAttributes(GameEvent event, Map<String, Object> eventAttributes) {
        if (eventAttributes == null) {
            return;
        }
        if (eventAttributes.containsKey("matchId")) {
            event.setMatchId(String.valueOf(eventAttributes.get("matchId")));
        }
        if (eventAttributes.containsKey("opponentPlayerId")) {
            event.setOpponentPlayerId(String.valueOf(eventAttributes.get("opponentPlayerId")));
        }
        if (eventAttributes.containsKey("result")) {
            event.setMatchResult(String.valueOf(eventAttributes.get("result")));
        }
        if (eventAttributes.containsKey("playerScore")) {
            event.setPlayerScore(toInt(eventAttributes.get("playerScore")));
        }
        if (eventAttributes.containsKey("opponentScore")) {
            event.setOpponentScore(toInt(eventAttributes.get("opponentScore")));
        }
        if (eventAttributes.containsKey("itemId")) {
            event.setItemId(String.valueOf(eventAttributes.get("itemId")));
        }
        if (eventAttributes.containsKey("currencyDelta")) {
            event.setCurrencyDelta(toLong(eventAttributes.get("currencyDelta")));
        }
        if (eventAttributes.containsKey("xpDelta")) {
            event.setXpDelta(toLong(eventAttributes.get("xpDelta")));
        }
        if (eventAttributes.containsKey("reason")) {
            event.setReason(String.valueOf(eventAttributes.get("reason")));
        }
    }


    /**
     * Builds a purchase event from a purchase request.
     *
     * <p>Uses a deterministic event id and sort key derived from {@code clientRequestId} so
     * TransactWrite retries collide on the same primary key: the event {@code Put} fails its
     * conditional check, the transaction rolls back, and the service returns
     * {@code IDEMPOTENT_REPLAY} without debiting the player twice. The {@code recordedAt} attribute
     * still records wall-clock time for auditing.
     *
     * @param playerId owner of the purchase
     * @param request  the incoming purchase request
     * @return a fully populated purchase {@link GameEvent} ready for persistence
     */
    public GameEvent toPurchaseEvent(String playerId, PurchaseRequest request) {
        // Deterministic UUID v3 from clientRequestId for idempotent transaction retries
        String eventId = UUID.nameUUIDFromBytes(
                request.clientRequestId().getBytes(StandardCharsets.UTF_8)).toString();
        String recordedAt = Instant.now().toString();

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + playerId);
        // Sort key uses "PURCHASE#" prefix instead of timestamp so retries collide on the same key
        event.setSortKey(GameEvent.SK_PREFIX + "PURCHASE#" + eventId);
        event.setEntityType(GameEvent.ENTITY_TYPE);
        event.setEventId(eventId);
        event.setPlayerId(playerId);
        event.setEventType(GameEventType.PURCHASE.name());
        event.setRecordedAt(recordedAt);
        event.setItemId(request.itemId());
        // Negative delta represents a debit from the player wallet
        event.setCurrencyDelta(-request.softCurrencyCost());

        if (ttlSeconds > 0) {
            event.setTtl(Instant.now().getEpochSecond() + ttlSeconds);
        }

        return event;
    }

    /**
     * Converts a game event model to the record-event response DTO.
     *
     * @param event the persisted game event
     * @return lightweight response containing the event id and recorded timestamp
     */
    public RecordEventResponse toRecordEventResponse(GameEvent event) {
        return new RecordEventResponse(event.getEventId(), event.getRecordedAt());
    }

    /**
     * Builds a {@link GameEventType#CURRENCY_GRANT} event from an earn request.
     *
     * <p>Uses a deterministic event id derived from {@code clientRequestId} so retries of the
     * {@link CurrencyRewardService}
     * earn transaction collide on the same GameEvents primary key. The {@code attribute_not_exists(PK)}
     * condition on the event put detects the collision and returns {@code IDEMPOTENT_REPLAY} without
     * crediting the player twice.
     *
     * @param playerId        player receiving the credit
     * @param amount          positive currency delta
     * @param reason          origin of the credit
     * @param clientRequestId caller-supplied idempotency key
     * @return ready-to-persist {@link GameEvent}
     */
    public GameEvent toCurrencyGrantEvent(String playerId, long amount,
                                          CurrencyEarnReason reason, String clientRequestId) {
        String eventId = UUID.nameUUIDFromBytes(
                clientRequestId.getBytes(StandardCharsets.UTF_8)).toString();
        String recordedAt = Instant.now().toString();

        GameEvent event = new GameEvent();
        event.setPartitionKey(GameEvent.PK_PREFIX + playerId);
        event.setSortKey(GameEvent.SK_PREFIX + "CURRENCY_GRANT#" + eventId);
        event.setEntityType(GameEvent.ENTITY_TYPE);
        event.setEventId(eventId);
        event.setPlayerId(playerId);
        event.setEventType(GameEventType.CURRENCY_GRANT.name());
        event.setRecordedAt(recordedAt);
        event.setCurrencyDelta(amount);
        event.setReason(reason.name());

        if (ttlSeconds > 0) {
            event.setTtl(Instant.now().getEpochSecond() + ttlSeconds);
        }

        return event;
    }

    /**
     * Converts a game event model to the paginated history DTO.
     *
     * <p>Reassembles non-null event-specific fields into the {@code eventAttributes} map.
     * The resulting map uses {@link LinkedHashMap} to preserve a stable key order
     * across serialisations.
     *
     * @param event the persisted game event
     * @return DTO with envelope fields and a dynamically built {@code eventAttributes} map
     */
    public GameEventDto toEventDto(GameEvent event) {
        // LinkedHashMap preserves insertion order for a predictable JSON eventAttributes object
        Map<String, Object> eventAttributes = new LinkedHashMap<>();
        if (event.getMatchId() != null) {
            eventAttributes.put("matchId", event.getMatchId());
        }
        if (event.getOpponentPlayerId() != null) {
            eventAttributes.put("opponentPlayerId", event.getOpponentPlayerId());
        }
        if (event.getMatchResult() != null) {
            eventAttributes.put("result", event.getMatchResult());
        }
        if (event.getPlayerScore() != null) {
            eventAttributes.put("playerScore", event.getPlayerScore());
        }
        if (event.getOpponentScore() != null) {
            eventAttributes.put("opponentScore", event.getOpponentScore());
        }
        if (event.getItemId() != null) {
            eventAttributes.put("itemId", event.getItemId());
        }
        if (event.getCurrencyDelta() != null) {
            eventAttributes.put("currencyDelta", event.getCurrencyDelta());
        }
        if (event.getXpDelta() != null) {
            eventAttributes.put("xpDelta", event.getXpDelta());
        }
        if (event.getReason() != null) {
            eventAttributes.put("reason", event.getReason());
        }

        return new GameEventDto(event.getEventId(), event.getEventType(), event.getRecordedAt(), eventAttributes);
    }

    /**
     * Parses an event attribute value to {@link Integer}.
     *
     * @param value number or string from the client event attribute map
     * @return parsed int
     */
    private static Integer toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    /**
     * Parses an event attribute value to {@link Long}.
     *
     * @param value number or string from the client event attribute map
     * @return parsed long
     */
    private static Long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }
}
