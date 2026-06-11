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
 * <p>Key pattern: {@code PK=ACCOUNT#{accountId}, SK=RESERVATION#{reservationId}}
 *
 * <p>Lifecycle: {@code ACTIVE} becomes {@code CONSUMED} when the payment completes, or
 * {@code ACTIVE} becomes {@code RELEASED} when the hold expires and the expiry sweeper restores
 * the available balance. Shares the same
 * partition key as {@link Account} so both are retrieved in a single Query (item collection pattern).
 *
 * <p>{@code expiresAt} is a Unix epoch <strong>second</strong> equal to {@code createdAtUtc} plus the
 * configured {@code dynamodb.reservation-timeout-seconds}. It is a plain numeric attribute, deliberately
 * <strong>not</strong> the table TTL attribute ({@code ttl}), so DynamoDB never deletes an expired
 * reservation. The sweeper instead transitions it to {@link ReservationStatus#RELEASED} in place, which
 * restores the held funds while keeping the row for audit.
 *
 * <p>UTC instants such as {@code createdAtUtc} use ISO-8601 with {@code Z} when represented as string attributes in DynamoDB.
 */
@DynamoDbBean
public class Reservation {

    /** Discriminator stored in {@code entityType}. */
    public static final String ENTITY_TYPE = "RESERVATION";
    /** Prefix for sort key: {@code RESERVATION#}{@code reservationId}. */
    public static final String KEY_PREFIX = "RESERVATION#";

    /** Partition key {@code PK} set to {@code ACCOUNT#}{@code accountId}. */
    private String accountKey;
    /** Sort key {@code SK} set to {@code RESERVATION#}{@code reservationId}. */
    private String reservationKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** Business reservation identifier. */
    private String reservationId;
    /** Payment that holds these reserved funds. */
    private String paymentId;
    /** Amount held against available balance. */
    private BigDecimal amount;
    /** Reservation lifecycle status. */
    private String status;
    /** UTC instant when the reservation was created. */
    private Instant createdAtUtc;
    /** Unix epoch second after which an {@code ACTIVE} hold is eligible for expiry release. */
    private Long expiresAt;

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

    @DynamoDbAttribute("expiresAt")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
