package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;
import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing an immutable financial ledger record.
 *
 * <p>Key pattern: {@code PK=ACCOUNT#{accountId}, SK=LEDGER#{timestamp}#{ledgerEntryId}}
 *
 * <p>Created when a payment completes (DEBIT entry). Never modified or deleted.
 * Shares the same partition key as {@link Account} (item collection pattern).
 *
 * <p>UTC instants such as {@code createdAtUtc} use ISO-8601 with {@code Z} when represented as string attributes in DynamoDB.
 */
@DynamoDbBean
public class LedgerEntry {

    /** Discriminator stored in {@code entityType}. */
    public static final String ENTITY_TYPE = "LEDGER_ENTRY";
    /** Prefix for sort key segment: {@code LEDGER#}… */
    public static final String KEY_PREFIX = "LEDGER#";

    private String accountKey;
    private String ledgerKey;
    private String entityType;
    private String ledgerEntryId;
    private String paymentId;
    private String entryType;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Instant createdAtUtc;

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
    public String getLedgerKey() {
        return ledgerKey;
    }

    public void setLedgerKey(String ledgerKey) {
        this.ledgerKey = ledgerKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getLedgerEntryId() {
        return ledgerEntryId;
    }

    public void setLedgerEntryId(String ledgerEntryId) {
        this.ledgerEntryId = ledgerEntryId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getEntryType() {
        return entryType;
    }

    public void setEntryType(String entryType) {
        this.entryType = entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(Instant createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }
}
