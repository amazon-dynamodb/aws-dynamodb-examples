package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model;

import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB bean representing a player's wallet stored in the PlayerState table.
 *
 * <p>Key layout: {@code PK = USER#<playerId>}, {@code SK = WALLET}.
 *
 * <p>Lives under the same partition as the {@link PlayerProfile} and {@link PlayerSettings}
 * rows, isolating currency writes (purchases, rewards) from progression writes (XP, level)
 * so the two never conflict on the same item's optimistic lock version.
 */
@DynamoDbBean
public class PlayerWallet {

    /** Value for the logical entity type attribute for wallet items. */
    public static final String ENTITY_TYPE = "PLAYER_WALLET";

    /** Sort key constant for the wallet item under a user partition. */
    public static final String SK_WALLET = "WALLET";

    /** Partition key. DynamoDB attribute {@code PK}. */
    private String partitionKey;

    /** Sort key. DynamoDB attribute {@code SK}. */
    private String sortKey;

    /** Discriminator value, typically {@link #ENTITY_TYPE}. */
    private String entityType;

    /** Business player id (denormalized for convenience). */
    private String playerId;

    /** Soft currency balance owned by the player. */
    private long currencyBalance;

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

    public long getCurrencyBalance() {
        return currencyBalance;
    }

    public void setCurrencyBalance(long currencyBalance) {
        this.currencyBalance = currencyBalance;
    }

    @DynamoDbVersionAttribute
    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
