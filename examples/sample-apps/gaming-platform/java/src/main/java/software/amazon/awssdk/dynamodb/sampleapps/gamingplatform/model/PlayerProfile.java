package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.Order;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a player profile stored in the PlayerState table.
 *
 * <p>Key layout: {@code PK = USER#<playerId>}, {@code SK = PROFILE}.
 *
 * <p>The {@code version} attribute is the optimistic-lock column for the enhanced DynamoDB client
 * ({@link DynamoDbVersionAttribute}).
 *
 * <p>Annotated for the {@code GSI_PLATFORM_PLAYERS} global secondary index with a composite
 * sort key ({@code lastUpdatedAt} FIRST, {@code playerId} SECOND). This enables queries
 * that browse recently active players filtered by platform.
 */
@DynamoDbBean
public class PlayerProfile {

    /** Value for the logical entity type attribute for profile items. */
    public static final String ENTITY_TYPE = "PLAYER_PROFILE";

    /** Sort key constant for the single profile item under a user partition. */
    public static final String SK_PROFILE = "PROFILE";

    /** Prefix for the partition key before the player id, stored as {@code PK}. */
    public static final String PK_PREFIX = "USER#";

    /** Name of the global secondary index used to list players by platform. */
    public static final String GSI_PLATFORM_PLAYERS = "GSI_PLATFORM_PLAYERS";

    /** Partition key. DynamoDB attribute {@code PK}. */
    private String partitionKey;

    /** Sort key. DynamoDB attribute {@code SK}. */
    private String sortKey;

    /** Discriminator value, typically {@link #ENTITY_TYPE}. */
    private String entityType;

    /** Business player id. Also part of the GSI composite sort key (second segment). */
    private String playerId;

    /** Platform name, for example IOS or ANDROID. GSI partition key. */
    private String platform;

    /** Opaque identity from the platform provider. */
    private String platformUserId;

    /** Display name chosen during registration. */
    private String playerName;

    /** Current progression level. */
    private int currentLevel;

    /** Total experience points accumulated. */
    private long totalExperience;


    /** Optimistic lock version for conditional writes on the enhanced client. */
    private long version;

    /** ISO-8601 UTC timestamp of last profile update. First segment of GSI composite sort key. */
    private String lastUpdatedAt;

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
     * Returns the entity type discriminator.
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
     * Player id. Second segment of the GSI composite sort key.
     *
     * @return player id
     */
    @DynamoDbSecondarySortKey(indexNames = GSI_PLATFORM_PLAYERS, order = Order.SECOND)
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
     * Platform name for GSI queries.
     *
     * @return platform
     */
    @DynamoDbSecondaryPartitionKey(indexNames = GSI_PLATFORM_PLAYERS)
    public String getPlatform() {
        return platform;
    }

    /**
     * Sets the platform.
     *
     * @param platform platform name
     */
    public void setPlatform(String platform) {
        this.platform = platform;
    }

    /**
     * Stable identity from the platform provider.
     *
     * @return platform user id
     */
    public String getPlatformUserId() {
        return platformUserId;
    }

    /**
     * Sets the platform user id.
     *
     * @param platformUserId platform user id
     */
    public void setPlatformUserId(String platformUserId) {
        this.platformUserId = platformUserId;
    }

    /**
     * Display name.
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
     * Current level.
     *
     * @return current level
     */
    public int getCurrentLevel() {
        return currentLevel;
    }

    /**
     * Sets the current level.
     *
     * @param currentLevel current level
     */
    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = currentLevel;
    }

    /**
     * Total XP.
     *
     * @return total experience
     */
    public long getTotalExperience() {
        return totalExperience;
    }

    /**
     * Sets total experience.
     *
     * @param totalExperience total experience
     */
    public void setTotalExperience(long totalExperience) {
        this.totalExperience = totalExperience;
    }


    /**
     * Optimistic lock version.
     *
     * @return version number
     */
    @DynamoDbVersionAttribute
    public long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic lock version.
     *
     * @param version version number
     */
    public void setVersion(long version) {
        this.version = version;
    }

    /**
     * Last update time in ISO-8601 UTC. First segment of the GSI composite sort key.
     *
     * @return last updated timestamp
     */
    @DynamoDbSecondarySortKey(indexNames = GSI_PLATFORM_PLAYERS, order = Order.FIRST)
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
