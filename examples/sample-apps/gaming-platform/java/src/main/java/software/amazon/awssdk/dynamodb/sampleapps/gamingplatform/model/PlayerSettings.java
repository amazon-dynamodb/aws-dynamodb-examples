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

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void setNotificationsEnabled(boolean notificationsEnabled) {
        this.notificationsEnabled = notificationsEnabled;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public void setPreferredLanguage(String preferredLanguage) {
        this.preferredLanguage = preferredLanguage;
    }

    public String getProfileVisibility() {
        return profileVisibility;
    }

    public void setProfileVisibility(String profileVisibility) {
        this.profileVisibility = profileVisibility;
    }

    @DynamoDbVersionAttribute
    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
