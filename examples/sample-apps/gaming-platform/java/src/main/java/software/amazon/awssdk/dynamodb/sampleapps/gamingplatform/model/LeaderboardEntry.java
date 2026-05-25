package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a leaderboard entry in the LeaderboardAggregate table.
 *
 * <p>Key layout: {@code PK = LEADERBOARD#<scope>} and
 * {@code SK = SCORE#<paddedScore>#USER#<playerId>}.
 * The padded score in the sort key enables descending {@code Query} for top-N retrieval.
 */
@DynamoDbBean
public class LeaderboardEntry {

    /** Value for the logical entity type attribute for leaderboard rows. */
    public static final String ENTITY_TYPE = "LEADERBOARD_ENTRY";

    /** Prefix for partition keys, before the scope segment. */
    public static final String PK_PREFIX = "LEADERBOARD#";

    /** Literal prefix inside the sort key before the zero-padded score. */
    public static final String SK_SCORE_PREFIX = "SCORE#";

    /** Separator between padded score and user id inside the sort key. */
    public static final String SK_USER_SEPARATOR = "#USER#";

    /** Zero-pad width for the numeric score segment inside {@code SK}. */
    public static final int SCORE_PAD_WIDTH = 10;

    /** Partition key. DynamoDB attribute {@code PK}. */
    private String partitionKey;

    /** Sort key. DynamoDB attribute {@code SK}. */
    private String sortKey;

    /** Discriminator value, typically {@link #ENTITY_TYPE}. */
    private String entityType;

    /** Player id denormalized onto the leaderboard row. */
    private String playerId;

    /** Display name snapshot for API responses. */
    private String playerName;

    /** Aggregate score for ranking. */
    private long score;

    /** ISO-8601 UTC timestamp of last projection update. */
    private String lastUpdatedAt;

    /**
     * Builds a padded sort key for the given score and player id.
     *
     * @param score    aggregate score
     * @param playerId player identifier
     * @return sort key in format {@code SCORE#0000002400#USER#abc}
     */
    public static String buildSortKey(long score, String playerId) {
        return SK_SCORE_PREFIX + String.format("%0" + SCORE_PAD_WIDTH + "d", score)
                + SK_USER_SEPARATOR + playerId;
    }

    /**
     * Builds the partition key for a leaderboard scope.
     *
     * @param scope the leaderboard scope (e.g. {@code SEASON#default#MODE#ranked})
     * @return partition key in format {@code LEADERBOARD#<scope>}
     */
    public static String buildPartitionKey(String scope) {
        return PK_PREFIX + scope;
    }

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
     * Player id on this leaderboard row.
     *
     * @return player id
     */
    public String getPlayerId() {
        return playerId;
    }

    /**
     * Sets the player id.
     *
     * @param playerId player id
     */
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    /**
     * Cached display name.
     *
     * @return player name
     */
    public String getPlayerName() {
        return playerName;
    }

    /**
     * Sets the display name.
     *
     * @param playerName display name
     */
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    /**
     * Leaderboard score used for ordering.
     *
     * @return score
     */
    public long getScore() {
        return score;
    }

    /**
     * Sets the score.
     *
     * @param score aggregate score
     */
    public void setScore(long score) {
        this.score = score;
    }

    /**
     * Last update time in ISO-8601 UTC.
     *
     * @return last updated timestamp
     */
    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     * Sets last updated timestamp.
     *
     * @param lastUpdatedAt ISO-8601 UTC timestamp
     */
    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}
