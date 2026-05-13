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

    private String accountKey;
    private String entityKey;
    private String entityType;
    private String accountId;
    private String status;
    private BigDecimal currentBalance;
    private BigDecimal availableBalance;
    private String currency;
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
