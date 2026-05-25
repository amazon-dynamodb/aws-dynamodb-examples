package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing player settings stored in the PlayerState table.
 *
 * <p>Key layout: {@code PK = USER#<playerId>}, {@code SK = SETTINGS}.
 *
 * <p>Lives under the same partition as the {@link PlayerProfile} row, enabling
 * transactional creation during registration and independent updates for
 * privacy and notification preferences without touching the profile item.
 *
 * <p>The {@code version} attribute provides optimistic locking so settings
 * updates do not conflict with concurrent progression or profile writes.
 */
@DynamoDbBean
public class PlayerSettings {

    /** Value for the logical entity type attribute for settings items. */
    public static final String ENTITY_TYPE = "PLAYER_SETTINGS";

    /** Sort key constant for the settings item under a user partition. */
    public static final String SK_SETTINGS = "SETTINGS";

    /** Partition key. DynamoDB attribute {@code PK}. */
    private String partitionKey;

    /** Sort key. DynamoDB attribute {@code SK}. */
    private String sortKey;

    /** Discriminator value, typically {@link #ENTITY_TYPE}. */
    private String entityType;

    /** Business player id (denormalized for convenience). */
    private String playerId;

    /** Whether push notifications are enabled. */
    private boolean notificationsEnabled;

    /** Preferred display language (e.g. {@code en}, {@code de}, {@code ja}). */
    private String preferredLanguage;

    /** Profile visibility: {@code PUBLIC}, {@code FRIENDS_ONLY}, or {@code PRIVATE}. */
    private String profileVisibility;

    /** Optimistic lock version for conditional writes. */
    private long version;

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
     * Player id (denormalized from the partition key).
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
     * Whether push notifications are enabled.
     *
     * @return {@code true} if notifications are enabled
     */
    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    /**
     * Sets the notifications-enabled flag.
     *
     * @param notificationsEnabled {@code true} to enable
     */
    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    /**
     * Preferred display language.
     *
     * @return language code
     */
    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    /**
     * Sets the preferred language.
     *
     * @param preferredLanguage language code
     */
    public void setPreferredLanguage(String preferredLanguage) {
        this.preferredLanguage = preferredLanguage;
    }

    /**
     * Profile visibility setting.
     *
     * @return visibility level
     */
    public String getProfileVisibility() {
        return profileVisibility;
    }

    /**
     * Sets the profile visibility.
     *
     * @param profileVisibility visibility level
     */
    public void setProfileVisibility(String profileVisibility) {
        this.profileVisibility = profileVisibility;
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
}

