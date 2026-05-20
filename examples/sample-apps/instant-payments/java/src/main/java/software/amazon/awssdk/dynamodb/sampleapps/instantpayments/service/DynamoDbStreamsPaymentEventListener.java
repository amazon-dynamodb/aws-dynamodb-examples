package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.DynamoDbTableInitializer;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Polls DynamoDB Streams for new {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} inserts and
 * triggers the payment processor.
 *
 * <p>In a production “event-driven” shape, an {@code OutboundPaymentInitiated} message might
 * invoke AWS Lambda. Here, the first domain event produces stream records, and this component
 * calls {@link OutboundPaymentProcessor#processPayment(String)}, the same method as the manual
 * REST trigger. Idempotency and safe retries are implemented in the processor and repositories,
 * not in stream delivery guarantees.
 *
 * <p>Uses {@link SmartLifecycle} to start polling after the Spring context is fully
 * initialised and to stop gracefully on shutdown.
 *
 * <p>This is a simple, single-threaded polling implementation suitable for a sample app.
 * Production workloads should use the Kinesis Client Library (KCL) for shard management,
 * checkpointing, and fault tolerance.
 *
 * <p>Shard iterator type is configurable via {@code dynamodb.streams.iterator-type} (default
 * {@link ShardIteratorType#LATEST}). Set {@link ShardIteratorType#TRIM_HORIZON} when you need reads from the
 * start of the stream retention window (for example in environments where {@code LATEST} would miss records).
 *
 * <p><strong>Checkpointing:</strong> For each shard id, the poller keeps {@code nextShardIterator}
 * and the last seen {@code sequenceNumber} in memory. Across poll ticks it resumes with
 * {@code GetRecords(nextShardIterator)} instead of reopening {@link ShardIteratorType#TRIM_HORIZON}
 * / {@link ShardIteratorType#LATEST}, which avoids both missing records and replaying the whole
 * retention window every second. Expired iterators are renewed with
 * {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER}. If data was trimmed, it falls back to the
 * configured iterator type. Checkpoints are not persisted. Process restart may re-read the stream
 * from the configured starting position.
 *
 * <p><strong>Poison-pill protection:</strong> If a record fails processing {@link #MAX_PROCESS_RETRIES}
 * times, it is skipped so the shard can make progress. The failure is logged at {@code ERROR} for
 * operational alerting. Retry counts are kept in memory per payment id and cleaned up on success or
 * after exhaustion.
 *
 * <p>{@link #MAX_GET_RECORDS_ROUNDS_PER_SHARD} caps {@code GetRecords} iterations per shard per poll tick so one busy shard
 * cannot starve the scheduler.
 */
@Component
@ConditionalOnProperty(name = "dynamodb.streams.enabled", havingValue = "true", matchIfMissing = true)
public class DynamoDbStreamsPaymentEventListener implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbStreamsPaymentEventListener.class);

    /**
     * Delay between poll loop iterations when the previous pass completed normally.
     */
    private static final long POLL_INTERVAL_MS = 1000;

    /**
     * Maximum records requested per {@code GetRecords} call (DynamoDB Streams cap applies per call).
     */
    private static final int POLL_LIMIT = 100;

    /**
     * Upper bound on {@code GetRecords} iterations per shard per scheduler tick so one hot shard cannot starve others.
     */
    private static final int MAX_GET_RECORDS_ROUNDS_PER_SHARD = 512;

    /**
     * After this many processing failures for the same {@code paymentId}, the record is skipped so the shard advances.
     */
    public static final int MAX_PROCESS_RETRIES = 3;

    /** Resolves table to stream ARN via {@code DescribeTable}. */
    private final DynamoDbAsyncClient dynamoDbClient;
    /** Opens shard iterators and reads stream records. */
    private final DynamoDbStreamsAsyncClient streamsClient;
    /** Runs {@link OutboundPaymentProcessor#processPayment(String)} for new outbound payments. */
    private final OutboundPaymentProcessor processor;
    /** Configured single-table name ({@code dynamodb.table-name}). */
    private final String tableName;
    /** Starting position for new shard iterators when no checkpoint exists. */
    private final ShardIteratorType shardIteratorType;

    /** Whether the poller lifecycle is active. */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** Per-shard in-memory iterator and sequence checkpoints. */
    private final ConcurrentHashMap<String, ShardCheckpoint> checkpoints = new ConcurrentHashMap<>();
    /** Processing failure counts per payment id for poison-pill handling. */
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();
    /** Single-thread scheduler that drives {@link #pollLoop}. */
    private ScheduledExecutorService scheduler;

    /**
     * @param dynamoDbClient      resolves table → stream ARN
     * @param streamsClient       shard iterators and records
     * @param processor           runs {@link OutboundPaymentProcessor#processPayment} for new payments
     * @param tableName           configured single-table name
     * @param iteratorTypeRaw     {@link ShardIteratorType#name()} e.g. {@code LATEST} (default) or {@code TRIM_HORIZON}
     */
    public DynamoDbStreamsPaymentEventListener(DynamoDbAsyncClient dynamoDbClient,
                                              DynamoDbStreamsAsyncClient streamsClient,
                                              OutboundPaymentProcessor processor,
                                              @Value("${dynamodb.table-name}") String tableName,
                                              @Value("${dynamodb.streams.iterator-type:LATEST}") String iteratorTypeRaw) {
        this.dynamoDbClient = dynamoDbClient;
        this.streamsClient = streamsClient;
        this.processor = processor;
        this.tableName = tableName;
        this.shardIteratorType = parseShardIteratorType(iteratorTypeRaw);
        logger.info("DynamoDB Streams payment event listener created: tableName={}, iteratorType={}",
                tableName, this.shardIteratorType);
    }

    /**
     * @param raw spring property value (case-insensitive). Unknown values fall back to {@link ShardIteratorType#LATEST}
     */
    private static ShardIteratorType parseShardIteratorType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ShardIteratorType.LATEST;
        }
        String normalized = raw.trim();
        for (ShardIteratorType candidate : ShardIteratorType.values()) {
            if (candidate.name().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return ShardIteratorType.LATEST;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts a daemon single-thread scheduler. First poll is delayed so the table and seed data exist.
     */
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("Starting DynamoDB Streams poller: tableName={}", tableName);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "streams-poller");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(this::pollLoop, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops scheduling and interrupts an in-flight poll.
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping DynamoDB Streams poller: tableName={}", tableName);
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Runs after most other beans: ensures {@link DynamoDbTableInitializer}
     * and repositories are ready before polling.
     *
     * @return {@link Integer#MAX_VALUE} so start happens late in the context lifecycle
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** Describes stream, walks shards once, processes records, then reschedules itself. */
    private void pollLoop() {
        try {
            String streamArn = discoverStreamArn();
            if (streamArn == null) {
                logger.warn("No stream ARN found for table; verify DynamoDB Streams is enabled: tableName={}", tableName);
                scheduleNextPoll();
                return;
            }

            List<Shard> shards = discoverShards(streamArn);
            for (Shard shard : shards) {
                if (!running.get()) break;
                pollShard(streamArn, shard);
            }
        } catch (Exception e) {
            logger.error("DynamoDB Streams poller error: tableName={}", tableName, e);
        }

        scheduleNextPoll();
    }

    /** Fixed-interval follow-up while {@link #running} and the scheduler is alive. */
    private void scheduleNextPoll() {
        if (running.get() && scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::pollLoop, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Table description exposes {@code latestStreamArn} when streams are enabled on create. */
    private String discoverStreamArn() {
        try {
            var tableDesc = dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build()).join();
            return tableDesc.table().latestStreamArn();
        } catch (Exception e) {
            logger.error("Failed to describe table for stream discovery: tableName={}", tableName, e);
            return null;
        }
    }

    /** Open shards for this poll cycle. */
    private List<Shard> discoverShards(String streamArn) {
        try {
            var response = streamsClient.describeStream(
                    DescribeStreamRequest.builder().streamArn(streamArn).build()).join();
            return response.streamDescription().shards();
        } catch (Exception e) {
            logger.error("Failed to describe stream: streamArn={}", streamArn, e);
            return List.of();
        }
    }

    /**
     * Reads new records for one shard, advancing the in-memory checkpoint.
     *
     * <p>Cold start uses {@link #shardIteratorType}. Steady state uses {@code GetRecords} with the
     * saved {@code nextShardIterator}. Iterator expiry and trim are handled in
     * {@link #getRecordsWithRenewal}.
     */
    private void pollShard(String streamArn, Shard shard) {
        String shardId = shard.shardId();
        ShardCheckpoint cp = checkpoints.computeIfAbsent(shardId, id -> new ShardCheckpoint());

        try {
            String iterator = cp.nextIterator;
            if (iterator == null) {
                iterator = openShardIterator(streamArn, shardId, cp);
                if (iterator == null) {
                    return;
                }
            }

            int rounds = 0;
            while (iterator != null && running.get() && rounds < MAX_GET_RECORDS_ROUNDS_PER_SHARD) {
                rounds++;
                GetRecordsResponse response = getRecordsWithRenewal(streamArn, shardId, cp, iterator);

                for (Record streamRecord : response.records()) {
                    if (!running.get()) {
                        return;
                    }
                    processStreamRecord(streamRecord);
                    // Advance sequence after successful handling so iterator renewal and poison-pill skips stay aligned.
                    String seq = streamRecord.dynamodb() != null ? streamRecord.dynamodb().sequenceNumber() : null;
                    if (seq != null && !seq.isBlank()) {
                        cp.lastSequenceNumber = seq;
                    }
                }

                iterator = response.nextShardIterator();
                cp.nextIterator = iterator;
            }
        } catch (Exception e) {
            logger.error("Error polling stream shard: tableName={}, shardId={}", tableName, shardId, e);
        }
    }

    /**
     * Calls {@code GetRecords}. On {@link ExpiredIteratorException}, clears the iterator and obtains
     * a new one via {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER} when possible, then retries
     * once (single retry path, further expiry is handled on the next poll round).
     */
    private GetRecordsResponse getRecordsWithRenewal(String streamArn,
                                                     String shardId,
                                                     ShardCheckpoint cp,
                                                     String iterator) {
        try {
            return streamsClient.getRecords(
                            GetRecordsRequest.builder()
                                    .shardIterator(iterator)
                                    .limit(POLL_LIMIT)
                                    .build())
                    .join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof ExpiredIteratorException) {
                logger.debug("Shard iterator expired, renewing: shardId={}, lastSequenceNumber={}",
                        shardId, cp.lastSequenceNumber != null ? cp.lastSequenceNumber : "(none)");
                cp.nextIterator = null;
                String fresh = openShardIterator(streamArn, shardId, cp);
                if (fresh == null) {
                    throw e;
                }
                return streamsClient.getRecords(
                                GetRecordsRequest.builder()
                                        .shardIterator(fresh)
                                        .limit(POLL_LIMIT)
                                        .build())
                        .join();
            }
            throw e;
        }
    }

    /**
     * Opens a shard iterator: {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER} when
     * {@link ShardCheckpoint#lastSequenceNumber} is set, otherwise {@link #shardIteratorType}.
     * If {@link TrimmedDataAccessException} occurs for {@code AFTER_SEQUENCE_NUMBER}, clears the
     * sequence and falls back to {@link #shardIteratorType}.
     */
    private String openShardIterator(String streamArn, String shardId, ShardCheckpoint cp) {
        boolean useAfterSequence = cp.lastSequenceNumber != null && !cp.lastSequenceNumber.isBlank();

        GetShardIteratorRequest.Builder req = GetShardIteratorRequest.builder()
                .streamArn(streamArn)
                .shardId(shardId);

        if (useAfterSequence) {
            req.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    .sequenceNumber(cp.lastSequenceNumber);
        } else {
            req.shardIteratorType(shardIteratorType);
        }

        try {
            String iterator = streamsClient.getShardIterator(req.build()).join().shardIterator();
            cp.nextIterator = iterator;
            return iterator;
        } catch (CompletionException e) {
            if (useAfterSequence && e.getCause() instanceof TrimmedDataAccessException) {
                logger.warn(
                        "Stream data trimmed before checkpoint; reopening shard iterator: tableName={}, shardId={}, "
                                + "lastSequenceNumber={}, iteratorType={}",
                        tableName,
                        shardId,
                        cp.lastSequenceNumber,
                        shardIteratorType);
                cp.lastSequenceNumber = null;
                String iterator = streamsClient.getShardIterator(
                                GetShardIteratorRequest.builder()
                                        .streamArn(streamArn)
                                        .shardId(shardId)
                                        .shardIteratorType(shardIteratorType)
                                        .build())
                        .join()
                        .shardIterator();
                cp.nextIterator = iterator;
                return iterator;
            }
            throw e;
        }
    }

    /**
     * In-memory checkpoint for one stream shard (not persisted across process restarts).
     *
     * <p>{@code nextIterator} holds the latest {@link GetRecordsResponse#nextShardIterator()} for the next poll.
     * {@code lastSequenceNumber} is the last processed {@link StreamRecord}
     * sequence and is used when renewing iterators with {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER}.
     */
    private static final class ShardCheckpoint {
        /** Latest {@link GetRecordsResponse#nextShardIterator()} for the next poll on this shard. */
        volatile String nextIterator;
        /** Last successfully processed stream record sequence for iterator renewal. */
        volatile String lastSequenceNumber;
    }

    /**
     * Filters to {@code INSERT} of {@link PaymentEvent#ENTITY_TYPE} with
     * {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} and delegates to the processor.
     *
     * <p><strong>Bounded retry:</strong> Processing failures are retried up to
     * {@link #MAX_PROCESS_RETRIES} times. While under the limit the exception is re-thrown,
     * causing {@link #pollShard} to stop iterating the current batch without advancing the
     * in-memory checkpoint. The next poll cycle resumes from the last successfully processed
     * sequence number. Once the limit is reached the record is treated as a poison pill. The
     * failure is logged at {@code ERROR} and the method returns normally so the checkpoint can
     * advance and the shard is unblocked.
     */
    private void processStreamRecord(Record streamRecord) {
        if (!"INSERT".equals(streamRecord.eventName().toString())) {
            return;
        }

        Map<String, AttributeValue> newImage = streamRecord.dynamodb().newImage();
        if (newImage == null) return;

        AttributeValue entityTypeAttr = newImage.get("entityType");
        if (entityTypeAttr == null || !PaymentEvent.ENTITY_TYPE.equals(entityTypeAttr.s())) {
            return;
        }

        AttributeValue eventTypeAttr = newImage.get("eventType");
        if (eventTypeAttr == null
                || !PaymentEventType.OUTBOUND_PAYMENT_CREATED.name().equals(eventTypeAttr.s())) {
            return;
        }

        AttributeValue paymentIdAttr = newImage.get("paymentId");
        if (paymentIdAttr == null) return;

        String paymentId = paymentIdAttr.s();
        logger.info("Outbound payment created event detected on stream: paymentId={}", paymentId);

        try {
            processor.processPayment(paymentId);
            retryCounts.remove(paymentId);
        } catch (Exception e) {
            int attempt = retryCounts.merge(paymentId, 1, Integer::sum);
            if (attempt >= MAX_PROCESS_RETRIES) {
                logger.error("Outbound payment processing exhausted retries, skipping poison pill: "
                                + "paymentId={}, attemptCount={}",
                        paymentId, attempt, e);
                retryCounts.remove(paymentId);
            } else {
                logger.warn("Outbound payment processing failed, will retry: paymentId={}, attempt={}/{}",
                        paymentId, attempt, MAX_PROCESS_RETRIES, e);
                throw e;
            }
        }
    }
}
