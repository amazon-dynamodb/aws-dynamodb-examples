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
    public String getEntityType() { return entityType; }

    /**
     * Sets the entity type discriminator.
     *
     * @param entityType entity type string
     */
    public void setEntityType(String entityType) { this.entityType = entityType; }

    /**
     * Business player id (denormalized from the partition key).
     *
     * @return player id
     */
    public String getPlayerId() { return playerId; }

    /**
     * Sets the player id.
     *
     * @param playerId player id
     */
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    /**
     * Current soft currency balance.
     *
     * @return currency balance
     */
    public long getCurrencyBalance() { return currencyBalance; }

    /**
     * Sets the currency balance.
     *
     * @param currencyBalance currency balance
     */
    public void setCurrencyBalance(long currencyBalance) { this.currencyBalance = currencyBalance; }

    /**
     * Optimistic lock version.
     *
     * @return version number
     */
    @DynamoDbVersionAttribute
    public long getVersion() { return version; }

    /**
     * Sets the optimistic lock version.
     *
     * @param version version number
     */
    public void setVersion(long version) { this.version = version; }
}
