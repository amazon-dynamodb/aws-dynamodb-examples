package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a leaderboard entry in the Leaderboard table.
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

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public long getScore() {
        return score;
    }

    public void setScore(long score) {
        this.score = score;
    }

    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}
