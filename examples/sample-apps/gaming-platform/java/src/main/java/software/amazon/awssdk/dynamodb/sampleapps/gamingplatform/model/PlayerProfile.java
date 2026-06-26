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

    @DynamoDbSecondarySortKey(indexNames = GSI_PLATFORM_PLAYERS, order = Order.SECOND)
    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_PLATFORM_PLAYERS)
    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getPlatformUserId() {
        return platformUserId;
    }

    public void setPlatformUserId(String platformUserId) {
        this.platformUserId = platformUserId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public void setCurrentLevel(int currentLevel) {
        this.currentLevel = currentLevel;
    }

    public long getTotalExperience() {
        return totalExperience;
    }

    public void setTotalExperience(long totalExperience) {
        this.totalExperience = totalExperience;
    }

    @DynamoDbVersionAttribute
    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_PLATFORM_PLAYERS, order = Order.FIRST)
    public String getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public void setLastUpdatedAt(String lastUpdatedAt) {
        this.lastUpdatedAt = lastUpdatedAt;
    }
}
