package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model;

import java.time.Instant;

import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.converter.ResponseSnapshotAttributeConverter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbConvertedBy;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item for idempotency tracking.
 *
 * <p>Key pattern: {@code PK=IDEMPOTENCY#{idempotencyKey}, SK=IDEMPOTENCY}
 *
 * <p>Stores the SHA-256 hash of the original request and a snapshot of the response.
 * On retry, the hash is compared to detect payload mismatches (409 Conflict) versus
 * legitimate retries (return stored response).
 *
 * <p>On outbound payment creation, this item is written inside {@code TransactWriteItems} with
 * {@code attribute_not_exists(PK)} so duplicate idempotency keys are rejected atomically;
 * the stream head and first event in that transaction are unconditional puts.
 *
 * <p>The client idempotency key is only encoded in {@code PK} ({@link #KEY_PREFIX} + key);
 * payment id is available from {@link #getResponseSnapshot()} when needed.
 *
 * <p>{@link #getExpiresAtEpochSecond()} is written to the DynamoDB attribute {@code ttl}: Unix
 * epoch <strong>second</strong> (instant) when this item becomes eligible for TTL deletion. Deletion is
 * <strong>eventual</strong> (not immediate). After the item is removed, reusing the same client
 * idempotency key represents a <strong>new</strong> logical create—align
 * {@code dynamodb.idempotency-ttl-seconds} with legal/operational retention policy.
 *
 * <p>{@code createdAtUtc} is when the idempotency binding was first stored. {@code expiresAtEpochSecond} maps to the DynamoDB
 * {@code ttl} attribute as Unix epoch <strong>seconds</strong> (eligible for eventual TTL deletion per table settings).
 */
@DynamoDbBean
public class IdempotencyRecord {

    /** Discriminator stored in {@code entityType}. */
    public static final String ENTITY_TYPE = "IDEMPOTENCY";
    /** Prefix for partition key: {@code IDEMPOTENCY#}{@code clientKey}. */
    public static final String KEY_PREFIX = "IDEMPOTENCY#";

    private String idempotencyRecordKey;
    private String entityKey;
    private String entityType;
    private String requestHash;
    private CreateOutboundPaymentResponse responseSnapshot;
    private Instant createdAtUtc;
    private Long expiresAtEpochSecond;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("PK")
    public String getIdempotencyRecordKey() {
        return idempotencyRecordKey;
    }

    public void setIdempotencyRecordKey(String idempotencyRecordKey) {
        this.idempotencyRecordKey = idempotencyRecordKey;
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

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    @DynamoDbConvertedBy(ResponseSnapshotAttributeConverter.class)
    public CreateOutboundPaymentResponse getResponseSnapshot() {
        return responseSnapshot;
    }

    public void setResponseSnapshot(CreateOutboundPaymentResponse responseSnapshot) {
        this.responseSnapshot = responseSnapshot;
    }

    public Instant getCreatedAtUtc() {
        return createdAtUtc;
    }

    public void setCreatedAtUtc(Instant createdAtUtc) {
        this.createdAtUtc = createdAtUtc;
    }

    @DynamoDbAttribute("ttl")
    public Long getExpiresAtEpochSecond() {
        return expiresAtEpochSecond;
    }

    public void setExpiresAtEpochSecond(Long expiresAtEpochSecond) {
        this.expiresAtEpochSecond = expiresAtEpochSecond;
    }
}
