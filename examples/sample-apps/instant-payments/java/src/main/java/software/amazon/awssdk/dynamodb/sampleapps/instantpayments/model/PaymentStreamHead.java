package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;
import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.mapper.Order;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.MerchantGsiProjectionAttributes;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * DynamoDB item used for optimistic concurrency on the payment event stream.
 *
 * <p>Key pattern: {@code PK=PAYMENT#{paymentId}, SK=#HEAD}
 *
 * <p>The stream head is <strong>not</strong> the full public read model for a single payment: API and
 * processors derive state by replaying {@link PaymentEvent} items. This row exists so
 * {@code TransactWriteItems} can compare-and-append atomically with financial side effects.
 *
 * <p><strong>Merchant list projections:</strong> the head row is enriched with
 * {@code merchantId}, {@code paymentId}, {@code createdAtUtc}, {@code correlationId}, monetary fields,
 * and {@code reasonCode} so it can be indexed by {@link #GSI_MERCHANT_PAYMENTS} and
 * {@link #GSI_MERCHANT_STATE_PAYMENTS} without a second read. Secondary key annotations on getters
 * map these attributes to multi-attribute GSI keys. INCLUDE non-key attribute names for
 * {@link #GSI_MERCHANT_STATE_PAYMENTS} live in
 * {@link MerchantGsiProjectionAttributes#GSI_MERCHANT_STATE_PAYMENTS_PROJECTED_NON_KEYS}.
 *
 * <p>{@code lastSequence} is the highest applied {@link PaymentEvent} sequence. {@code aggregateState} is the
 * corresponding aggregate state name ({@link PaymentState}) and must stay aligned with replay of events through
 * {@link PaymentEventReplayer} for correct reads and GSI keys.
 */
@DynamoDbBean
public class PaymentStreamHead {

    /**
     * Value stored in {@code entityType} for this item shape (stream head row).
     */
    public static final String ENTITY_TYPE = "PAYMENT_STREAM_HEAD";

    /**
     * Fixed {@code SK} for the concurrency row. Payment events use {@code EVENT#…} sort keys instead.
     */
    public static final String SORT_KEY = "#HEAD";

    /**
     * Global secondary index: list a merchant's payments, newest first, using {@code merchantId} and a composite sort key
     * ({@code createdAtUtc}, {@code paymentId}).
     */
    public static final String GSI_MERCHANT_PAYMENTS = "GSI_MERCHANT_PAYMENTS";

    /**
     * Global secondary index: list a merchant's payments filtered by {@code aggregateState}, ordered by {@code createdAtUtc}.
     */
    public static final String GSI_MERCHANT_STATE_PAYMENTS = "GSI_MERCHANT_STATE_PAYMENTS";

    /** Partition key {@code PK} set to {@code PAYMENT#}{@code paymentId}. */
    private String paymentKey;
    /** Sort key {@code SK} fixed to {@code #HEAD} for the concurrency row. */
    private String streamKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** Highest applied {@link PaymentEvent} sequence number. */
    private long lastSequence;
    /** Aggregate payment state used for GSI keys and conditional transitions. */
    private String aggregateState;
    /** UTC instant when the stream head was last updated. */
    private Instant updatedAtUtc;

    /** Payment identifier projected for merchant GSI sort keys. */
    private String paymentId;
    /** Merchant identifier projected as GSI partition key. */
    private String merchantId;
    /** Payment creation time projected as GSI sort key. */
    private Instant createdAtUtc;
    /** Correlation identifier projected for merchant list responses. */
    private String correlationId;
    /** Payment amount projected for merchant list responses. */
    private BigDecimal amount;
    /** Currency code projected for merchant list responses. */
    private String currency;
    /** Rejection reason projected when the payment is rejected. */
    private String reasonCode;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getPaymentKey() {
        return paymentKey;
    }

    public void setPaymentKey(String paymentKey) {
        this.paymentKey = paymentKey;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = streamKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public long getLastSequence() {
        return lastSequence;
    }

    public void setLastSequence(long lastSequence) {
        this.lastSequence = lastSequence;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_MERCHANT_STATE_PAYMENTS, order = Order.SECOND)
    public String getAggregateState() {
        return aggregateState;
    }

    public void setAggregateState(String aggregateState) {
        this.aggregateState = aggregateState;
    }

    public Instant getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public void setUpdatedAtUtc(Instant updatedAtUtc) {
        this.updatedAtUtc = updatedAtUtc;
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_MERCHANT_PAYMENTS, order = Order.SECOND)
    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = GSI_MERCHANT_PAYMENTS)
    @DynamoDbSecondaryPartitionKey(indexNames = GSI_MERCHANT_STATE_PAYMENTS, order = Order.FIRST)
    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    @DynamoDbSecondarySortKey(indexNames = GSI_MERCHANT_PAYMENTS, order = Order.FIRST)
    @DynamoDbSecondarySortKey(indexNames = GSI_MERCHANT_STATE_PAYMENTS)
    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(Instant createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
