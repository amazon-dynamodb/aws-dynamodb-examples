package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.math.BigDecimal;
import java.time.Instant;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing a funds reservation for an in-flight payment.
 *
 * <p>Key pattern for the audit row: {@code PK=ACCOUNT#{accountId}, SK=RESERVATION#{reservationId}}.
 * Each hold is written as two rows in the same account partition. The audit row carries the lifecycle
 * {@code status} and never expires. A second short lived temporary reservation row uses the same bean with
 * {@code SK=RESERVATION_TEMP#{reservationId}}, {@code entityType=RESERVATION_TEMP}, and the table TTL
 * attribute {@code ttl} set. DynamoDB deletes the temporary reservation once {@code ttl} passes, and the resulting
 * stream {@code REMOVE} record is what drives the release of an abandoned hold.
 *
 * <p>Lifecycle of the audit row: {@code ACTIVE} becomes {@code CONSUMED} when the payment completes,
 * or {@code ACTIVE} becomes {@code RELEASED} when the temporary reservation is deleted and the streams listener
 * restores the available balance. Both rows share the account partition key with {@link Account}, so
 * the account and its reservations are retrieved in a single Query (item collection pattern).
 *
 * <p>Only the temporary reservation row sets {@code ttl} (a Unix epoch second equal to {@code createdAtUtc} plus the
 * configured {@code dynamodb.reservation-timeout-seconds}). The audit row leaves {@code ttl} null so
 * DynamoDB never deletes it, which keeps the hold auditable after release.
 *
 * <p>Both rows also carry {@code expiresAt}, a Unix epoch second equal to the temporary reservation's {@code ttl}
 * value. Unlike {@code ttl}, {@code expiresAt} is not the table time-to-live attribute, so DynamoDB never deletes a
 * row because of it. It is the deadline the complete transaction guards against. DynamoDB TTL deletes a marker on a
 * best-effort schedule that can lag the {@code ttl} timestamp by many hours, so the marker can still exist after the
 * hold has logically expired. The complete transaction therefore requires {@code expiresAt} to be in the future, which
 * stops a late completion from settling a stale hold during that grace window. The audit row keeps {@code ttl} null so
 * it stays auditable while still carrying {@code expiresAt} for the guard.
 *
 * <p>UTC instants such as {@code createdAtUtc} use ISO-8601 with {@code Z} when represented as string attributes in DynamoDB.
 */
@DynamoDbBean
public class Reservation {

    /** Discriminator stored in {@code entityType} for the audit row. */
    public static final String ENTITY_TYPE = "RESERVATION";
    /** Prefix for the audit row sort key: {@code RESERVATION#}{@code reservationId}. */
    public static final String KEY_PREFIX = "RESERVATION#";
    /** Discriminator stored in {@code entityType} for the temporary reservation row. */
    public static final String TEMPORARY_ENTITY_TYPE = "RESERVATION_TEMP";
    /** Prefix for the temporary reservation row sort key: {@code RESERVATION_TEMP#}{@code reservationId}. */
    public static final String TEMPORARY_KEY_PREFIX = "RESERVATION_TEMP#";

    /** Partition key {@code PK} set to {@code ACCOUNT#}{@code accountId}. */
    private String accountKey;
    /** Sort key {@code SK} set to the audit or temporary reservation prefix plus {@code reservationId}. */
    private String reservationKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** Business reservation identifier shared by the audit row and its temporary reservation. */
    private String reservationId;
    /** Payment that holds these reserved funds. */
    private String paymentId;
    /** Amount held against available balance. */
    private BigDecimal amount;
    /** Reservation lifecycle status. */
    private String status;
    /** UTC instant when the reservation was created. */
    private Instant createdAtUtc;
    /**
     * DynamoDB time-to-live as a Unix epoch second, mapped to the table {@code ttl} attribute. Set only on the
     * temporary reservation row ({@code createdAtUtc} plus {@code dynamodb.reservation-timeout-seconds}). The audit
     * row leaves it null so it never expires.
     */
    private Long ttl;

    /**
     * Hold deadline as a Unix epoch second, mapped to the table {@code expiresAt} attribute. Set on both rows to the
     * same value as the temporary reservation's {@code ttl} ({@code createdAtUtc} plus
     * {@code dynamodb.reservation-timeout-seconds}). It is not the table time-to-live attribute, so DynamoDB never
     * deletes a row because of it. The complete transaction reads it to reject a settlement that arrives after the
     * deadline, even while a marker still physically exists during the DynamoDB TTL grace window.
     */
    private Long expiresAtEpochSecond;

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
    public String getReservationKey() {
        return reservationKey;
    }

    public void setReservationKey(String reservationKey) {
        this.reservationKey = reservationKey;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(Instant createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }

    /**
     * @return hold deadline as a Unix epoch second, mapped to the {@code expiresAt} attribute, or null when unset
     */
    @DynamoDbAttribute("expiresAt")
    public Long getExpiresAtEpochSecond() {
        return expiresAtEpochSecond;
    }

    /**
     * @param expiresAtEpochSecond hold deadline as a Unix epoch second, stored under {@code expiresAt}
     */
    public void setExpiresAtEpochSecond(Long expiresAtEpochSecond) {
        this.expiresAtEpochSecond = expiresAtEpochSecond;
    }
}
