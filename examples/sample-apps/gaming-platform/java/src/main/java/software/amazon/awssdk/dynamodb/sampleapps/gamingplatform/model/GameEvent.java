package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a game event stored in the GameEvents table.
 *
 * <p>Key layout: {@code PK = USER#<playerId>}. General events use
 * {@code SK = EVT#<ISO8601>#<eventId>}. Purchase idempotency uses a fixed
 * {@code SK = EVT#PURCHASE#<eventId>} where {@code eventId} is derived from the client's
 * idempotency key so retries target one DynamoDB item.
 *
 * <p>Events may carry a {@code ttl} attribute in epoch seconds for DynamoDB time-to-live expiry.
 * Nullable fields cover different event types such as
 * PVP_MATCH, PURCHASE, LEVEL_PROGRESS, and CURRENCY_GRANT.
 */
@DynamoDbBean
public class GameEvent {

    /** Value for the logical entity type attribute for event items. */
    public static final String ENTITY_TYPE = "GAME_EVENT";

    /** Prefix for the partition key before the player id. */
    public static final String PK_PREFIX = "USER#";

    /** Prefix for sort keys on event items. */
    public static final String SK_PREFIX = "EVT#";

    /** Partition key. DynamoDB attribute {@code PK}. */
    private String partitionKey;

    /** Sort key. DynamoDB attribute {@code SK}. */
    private String sortKey;

    /** Discriminator value, typically {@link #ENTITY_TYPE}. */
    private String entityType;

    /** Service-generated unique id for this event. */
    private String eventId;

    /** Player who owns this event row. */
    private String playerId;

    /** Event type name, for example PVP_MATCH or PURCHASE. */
    private String eventType;

    /** ISO-8601 timestamp when the event was recorded. */
    private String recordedAt;

    /** PVP only. Score for the player. */
    private Integer playerScore;

    /** PVP only. Score for the opponent. */
    private Integer opponentScore;

    /** PVP only. Outcome label, for example WIN. */
    private String matchResult;

    /** PVP only. Opponent player id. */
    private String opponentPlayerId;

    /** PVP only. Match correlation id. */
    private String matchId;

    /** Purchase only. Catalog item id. */
    private String itemId;

    /** Purchase or economy event. Currency delta. */
    private Long currencyDelta;

    /** Progression events. Experience delta. */
    private Long xpDelta;

    /** Grant or economy reason text. */
    private String reason;

    /** DynamoDB TTL in epoch seconds. May be null when not set. */
    private Long ttl;

    /**
     * Partition key (DynamoDB {@code PK}).
     *
     * @return partition key value
     */
    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPartitionKey() {
        return partitionKey;
    }

    /**
     * Sets the partition key.
     *
     * @param partitionKey partition key value
     */
    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    /**
     * Sort key (DynamoDB {@code SK}).
     *
     * @return sort key value
     */
    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSortKey() {
        return sortKey;
    }

    /**
     * Sets the sort key.
     *
     * @param sortKey sort key value
     */
    public void setSortKey(String sortKey) {
        this.sortKey = sortKey;
    }

    /**
     * Entity type discriminator.
     *
     * @return entity type string
     */
    public String getEntityType() {
        return entityType;
    }

    /**
     * Sets the entity type discriminator.
     *
     * @param entityType entity type string
     */
    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    /**
     * Event id.
     *
     * @return event id
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * Sets the event id.
     *
     * @param eventId event id
     */
    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    /**
     * Owner player id.
     *
     * @return player id
     */
    public String getPlayerId() {
        return playerId;
    }

    /**
     * Sets the owner player id.
     *
     * @param playerId player id
     */
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    /**
     * Event type.
     *
     * @return event type name
     */
    public String getEventType() {
        return eventType;
    }

    /**
     * Sets the event type.
     *
     * @param eventType event type name
     */
    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    /**
     * Event timestamp in ISO-8601.
     *
     * @return timestamp string
     */
    @DynamoDbAttribute("recordedAt")
    public String getRecordedAt() {
        return recordedAt;
    }

    /**
     * Sets the event timestamp.
     *
     * @param recordedAt ISO-8601 timestamp
     */
    public void setRecordedAt(String recordedAt) {
        this.recordedAt = recordedAt;
    }

    /**
     * PVP score for the player.
     *
     * @return score or null
     */
    public Integer getPlayerScore() {
        return playerScore;
    }

    /**
     * Sets the PVP score for the player.
     *
     * @param playerScore score or null
     */
    public void setPlayerScore(Integer playerScore) {
        this.playerScore = playerScore;
    }

    /**
     * PVP score for the opponent.
     *
     * @return score or null
     */
    public Integer getOpponentScore() {
        return opponentScore;
    }

    /**
     * Sets the PVP score for the opponent.
     *
     * @param opponentScore score or null
     */
    public void setOpponentScore(Integer opponentScore) {
        this.opponentScore = opponentScore;
    }

    /**
     * PVP match result.
     *
     * @return result label or null
     */
    public String getMatchResult() {
        return matchResult;
    }

    /**
     * Sets PVP match result.
     *
     * @param matchResult result label or null
     */
    public void setMatchResult(String matchResult) {
        this.matchResult = matchResult;
    }

    /**
     * PVP opponent id.
     *
     * @return opponent player id or null
     */
    public String getOpponentPlayerId() {
        return opponentPlayerId;
    }

    /**
     * Sets PVP opponent id.
     *
     * @param opponentPlayerId opponent player id or null
     */
    public void setOpponentPlayerId(String opponentPlayerId) {
        this.opponentPlayerId = opponentPlayerId;
    }

    /**
     * PVP match id.
     *
     * @return match id or null
     */
    public String getMatchId() {
        return matchId;
    }

    /**
     * Sets PVP match id.
     *
     * @param matchId match id or null
     */
    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    /**
     * Purchase item id.
     *
     * @return item id or null
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets purchase item id.
     *
     * @param itemId item id or null
     */
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /**
     * Currency delta for economy or purchase events.
     *
     * @return delta or null
     */
    public Long getCurrencyDelta() {
        return currencyDelta;
    }

    /**
     * Sets currency delta.
     *
     * @param currencyDelta delta or null
     */
    public void setCurrencyDelta(Long currencyDelta) {
        this.currencyDelta = currencyDelta;
    }

    /**
     * Experience delta for progression-related events.
     *
     * @return delta or null
     */
    public Long getXpDelta() {
        return xpDelta;
    }

    /**
     * Sets experience delta.
     *
     * @param xpDelta delta or null
     */
    public void setXpDelta(Long xpDelta) {
        this.xpDelta = xpDelta;
    }

    /**
     * Reason text for grants or similar events.
     *
     * @return reason or null
     */
    public String getReason() {
        return reason;
    }

    /**
     * Sets reason text.
     *
     * @param reason reason or null
     */
    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * TTL attribute in epoch seconds when present.
     *
     * @return ttl or null
     */
    public Long getTtl() {
        return ttl;
    }

    /**
     * Sets TTL in epoch seconds.
     *
     * @param ttl ttl or null
     */
    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }
}
