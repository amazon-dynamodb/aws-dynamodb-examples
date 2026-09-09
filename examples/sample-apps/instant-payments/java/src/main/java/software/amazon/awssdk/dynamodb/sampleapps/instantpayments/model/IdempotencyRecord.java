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
 * {@code attribute_not_exists(PK)} so duplicate idempotency keys are rejected atomically.
 * The stream head and first event in that transaction are unconditional puts.
 *
 * <p>The client idempotency key is only encoded in {@code PK} ({@link #KEY_PREFIX} + key).
 * Payment id is available from {@link #responseSnapshot} when needed.
 *
 * <p>{@link #expiresAtEpochSecond} is written to the DynamoDB attribute {@code ttl}. Unix
 * epoch <strong>second</strong> (instant) when this item becomes eligible for TTL deletion. Deletion is
 * <strong>eventual</strong> (not immediate). After the item is removed, reusing the same client
 * idempotency key represents a <strong>new</strong> logical create. Align
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

    /** Partition key {@code PK} set to {@code IDEMPOTENCY#}{@code clientKey}. */
    private String idempotencyRecordKey;
    /** Sort key {@code SK} fixed to {@code IDEMPOTENCY}. */
    private String entityKey;
    /** Item discriminator stored in {@code entityType}. */
    private String entityType;
    /** SHA-256 hash of the original create request body. */
    private String requestHash;
    /** Stored create response returned on legitimate retries. */
    private CreateOutboundPaymentResponse responseSnapshot;
    /** UTC instant when the idempotency binding was first stored. */
    private Instant createdAtUtc;
    /** TTL attribute {@code ttl} as Unix epoch seconds for eventual deletion. */
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
