package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.extensions.annotations.DynamoDbVersionAttribute;

/**
 * DynamoDB item representing a financial account.
 *
 * <p>Key pattern: {@code PK=ACCOUNT#{accountId}, SK=ACCOUNT#{accountId}}
 *
 * <p>Shares the partition key with {@link Reservation} and {@link LedgerEntry}
 * so a single Query returns the account and all related items (item collection pattern).
 */
@DynamoDbBean
public class Account {

    /** Discriminator stored in {@code entityType}. */
    public static final String ENTITY_TYPE = "ACCOUNT";
    /** Prefix for keys: {@code ACCOUNT#}{@code accountId}. */
    public static final String KEY_PREFIX = "ACCOUNT#";

    /** Partition key {@code PK} set to {@code ACCOUNT#}{@code accountId}. */
    private String accountKey;
    /** Sort key {@code SK} set to {@code ACCOUNT#}{@code accountId}. */
    private String entityKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** Business account identifier. */
    private String accountId;
    /** Account lifecycle status. */
    private String status;
    /** Posted balance after settled debits. */
    private BigDecimal currentBalance;
    /** Spendable balance after active reservations. */
    private BigDecimal availableBalance;
    /** ISO currency code for balances. */
    private String currency;
    /** Optimistic locking version for conditional updates. */
    private int version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getAccountKey() {
        return accountKey;
    }

    public void setAccountKey(String accountKey) {
        this.accountKey = accountKey;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getEntityKey() {
        return entityKey;
    }

    public void setEntityKey(String entityKey) {
        this.entityKey = entityKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public void setAvailableBalance(BigDecimal availableBalance) {
        this.availableBalance = availableBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @DynamoDbVersionAttribute
    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
