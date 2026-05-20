package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;
import java.time.Instant;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.PaymentEventReplayer;

/**
 * In-memory view of an outbound payment produced by {@link PaymentEventReplayer}.
 *
 * <p>In-memory-only: built by {@link PaymentEventReplayer}
 * from the event stream under {@code PK=PAYMENT#{id}}. Field layout matches outbound payment APIs and DTOs.
 *
 * <p>{@link #KEY_PREFIX} prefixes partition keys. {@code createdAtUtc} and {@code updatedAtUtc} are UTC instants
 * (ISO-8601 with {@code Z} on the wire). {@code version} matches {@link PaymentEvent#getSequenceNumber()} after the latest applied event.
 */
public class Payment {

    /**
     * Prefix for the payment partition key: {@code PAYMENT#}{@code paymentId} is stored as {@code PK} (and ties the item collection).
     */
    public static final String KEY_PREFIX = "PAYMENT#";

    /** Payment partition key {@code PAYMENT#}{@code paymentId} from the event stream. */
    private String paymentKey;
    /** Unique payment identifier. */
    private String paymentId;
    /** Merchant that initiated the payment. */
    private String merchantId;
    /** Aggregate payment state after replaying events. */
    private String state;
    /** Debtor account debited for the payment. */
    private String debtorAccountId;
    /** Creditor IBAN for the outbound transfer. */
    private String creditorIban;
    /** Creditor display name. */
    private String creditorName;
    /** Payment amount. */
    private BigDecimal amount;
    /** ISO currency code for the amount. */
    private String currency;
    /** Client idempotency key from the create request. */
    private String idempotencyKey;
    /** Correlation identifier for tracing. */
    private String correlationId;
    /** Rejection reason when the payment failed. */
    private String reasonCode;
    /** UTC instant when the payment was created. */
    private Instant createdAtUtc;
    /** UTC instant when the payment last changed state. */
    private Instant updatedAtUtc;
    /** Latest applied event sequence number. */
    private int version;

    public String getPaymentKey() {
        return paymentKey;
    }

    public void setPaymentKey(String paymentKey) {
        this.paymentKey = paymentKey;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
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

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(Instant createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    public Instant getUpdatedAtUtc() {
        return updatedAtUtc;
    }

    public void setUpdatedAtUtc(Instant updatedAtUtc) {
        this.updatedAtUtc = updatedAtUtc;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
