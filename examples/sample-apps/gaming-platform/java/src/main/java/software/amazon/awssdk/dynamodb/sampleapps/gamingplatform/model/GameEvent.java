package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a game event stored in the GameEvent table.
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

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPartitionKey() {
        return partitionKey;
    }

    public void setPartitionKey(String partitionKey) {
        this.partitionKey = partitionKey;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSortKey() {
        return sortKey;
    }

    public void setSortKey(String sortKey) {
        this.sortKey = sortKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    @DynamoDbAttribute("recordedAt")
    public String getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(String recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Integer getPlayerScore() {
        return playerScore;
    }

    public void setPlayerScore(Integer playerScore) {
        this.playerScore = playerScore;
    }

    public Integer getOpponentScore() {
        return opponentScore;
    }

    public void setOpponentScore(Integer opponentScore) {
        this.opponentScore = opponentScore;
    }

    public String getMatchResult() {
        return matchResult;
    }

    public void setMatchResult(String matchResult) {
        this.matchResult = matchResult;
    }

    public String getOpponentPlayerId() {
        return opponentPlayerId;
    }

    public void setOpponentPlayerId(String opponentPlayerId) {
        this.opponentPlayerId = opponentPlayerId;
    }

    public String getMatchId() {
        return matchId;
    }

    public void setMatchId(String matchId) {
        this.matchId = matchId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public Long getCurrencyDelta() {
        return currencyDelta;
    }

    public void setCurrencyDelta(Long currencyDelta) {
        this.currencyDelta = currencyDelta;
    }

    public Long getXpDelta() {
        return xpDelta;
    }

    public void setXpDelta(Long xpDelta) {
        this.xpDelta = xpDelta;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }
}
