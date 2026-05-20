package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;
import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * DynamoDB item: one append-only event in the payment stream.
 *
 * <p>Key pattern: {@code PK=PAYMENT#{paymentId}, SK=EVENT#{zero-padded sequence}}
 *
 * <p>Lifecycle and audit narrative are carried by {@link #getEventType()} ({@link PaymentEventType}):
 * {@code OUTBOUND_PAYMENT_CREATED}, {@code FUNDS_RESERVED}, {@code COMPLETED}, {@code REJECTED}.
 * Optional {@link #getReasonCode()} explains terminal failure for {@code REJECTED}. DynamoDB may
 * still return historical attributes (e.g. legacy transition columns). They are not mapped on this
 * bean and do not affect {@link PaymentEventReplayer}.
 *
 * <p>{@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} carries payment shell attributes. Later events
 * may leave optional fields {@code null}.
 */
@DynamoDbBean
public class PaymentEvent {

    /** Discriminator stored in {@code entityType}. */
    public static final String ENTITY_TYPE = "PAYMENT_EVENT";
    /** Prefix for event sort keys: {@code EVENT#} + fixed-width sequence. */
    public static final String KEY_PREFIX = "EVENT#";
    /** Width for numeric suffix so lexicographic order matches numeric order. */
    public static final int SEQUENCE_PAD = 19;

    /** Partition key {@code PK} set to {@code PAYMENT#}{@code paymentId}. */
    private String paymentKey;
    /** Sort key {@code SK} set to {@code EVENT#} plus zero-padded sequence. */
    private String eventKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** Lifecycle event name such as {@code OUTBOUND_PAYMENT_CREATED}. */
    private String eventType;
    /** Monotonic 1-based position in the payment event stream. */
    private long sequenceNumber;
    /** Correlation identifier carried with the event. */
    private String correlationId;
    /** Rejection reason when the event type is terminal failure. */
    private String reasonCode;
    /** UTC instant when the event was recorded. */
    private Instant occurredAt;

    /** Payment identifier copied on the creation event. */
    private String paymentId;
    /** Merchant identifier copied on the creation event. */
    private String merchantId;
    /** Debtor account identifier copied on the creation event. */
    private String debtorAccountId;
    /** Creditor IBAN copied on the creation event. */
    private String creditorIban;
    /** Creditor name copied on the creation event. */
    private String creditorName;
    /** Payment amount copied on the creation event. */
    private BigDecimal amount;
    /** Currency code copied on the creation event. */
    private String currency;
    /** Client idempotency key copied on the creation event. */
    private String idempotencyKey;

    /**
     * @param sequence 1-based stream position
     * @return {@code EVENT#000...000seq}
     */
    public static String sortKeyForSequence(long sequence) {
        return KEY_PREFIX + String.format("%0" + SEQUENCE_PAD + "d", sequence);
    }

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
    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getDebtorAccountId() {
        return debtorAccountId;
    }

    public void setDebtorAccountId(String debtorAccountId) {
        this.debtorAccountId = debtorAccountId;
    }

    public String getCreditorIban() {
        return creditorIban;
    }

    public void setCreditorIban(String creditorIban) {
        this.creditorIban = creditorIban;
    }

    public String getCreditorName() {
        return creditorName;
    }

    public void setCreditorName(String creditorName) {
        this.creditorName = creditorName;
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
