package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Account;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEvent;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentEventType;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Reservation;
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
 * <p>Polls DynamoDB Streams for new {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} inserts and
 * triggers the payment processor.
 *
 * <p><strong>Reservation expiry:</strong> the poller also reacts to {@code REMOVE} records for
 * {@code RESERVATION_TEMP#} temporary reservation rows. Each hold is written as an audit row plus a temporary reservation row that
 * carries the table {@code ttl} attribute. When DynamoDB deletes the temporary reservation, the resulting
 * {@code REMOVE} record drives {@link OutboundPaymentProcessor#releaseExpiredReservation(String, String)},
 * which restores the held funds if the hold never completed. The temporary reservation keys travel on the
 * {@code REMOVE} record regardless of the stream view type, so the table can keep
 * {@link ShardIteratorType#LATEST}-friendly {@code NEW_IMAGE} streams without carrying old images for
 * every change.
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
 * @apiNote The poller calls {@link OutboundPaymentProcessor#processPayment(String)} with
 * {@code .join()} on its dedicated scheduler thread. That blocking is acceptable here: it is not a
 * Tomcat worker thread, and sequential per-record processing matches this sample's consumption model.
 *
 * <p><strong>Shard discovery:</strong> {@code DescribeStream} returns at most 100 shards per page
 * and sets {@code lastEvaluatedShardId} when more exist. The poller pages with
 * {@code exclusiveStartShardId} until {@code lastEvaluatedShardId} is null, so shards beyond the
 * first page are not skipped on a heavily resharded table.
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
 * from the configured starting position. Checkpoints for shards no longer returned by
 * {@code DescribeStream} are pruned after each discovery pass so closed-shard entries do not accumulate.
 *
 * <p><strong>Poison-pill protection:</strong> If a record fails processing {@link #MAX_PROCESS_RETRIES}
 * times, it is skipped so the shard can make progress. The failure is logged at {@code ERROR} for
 * operational alerting. Retry counts are kept in memory per payment id and cleaned up on success or
 * after exhaustion. An id that fails transiently and never reappears is bounded by an access-ordered
 * LRU capped at {@link #MAX_RETRY_COUNT_ENTRIES}, so the retry map stays flat on a long-running pod.
 *
 * <p>{@link #MAX_GET_RECORDS_ROUNDS_PER_SHARD} caps {@code GetRecords} iterations per shard per poll tick so one busy shard
 * cannot starve the scheduler.
 *
 * <p><strong>Read cost:</strong> a raw DynamoDB stream carries <em>every</em> table change, and DynamoDB
 * Streams has no server-side filter. This poller therefore reads all records and filters in code
 * ({@link #processStreamRecord} keeps only {@link PaymentEvent#ENTITY_TYPE} inserts of
 * {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED}), so it pays {@code GetRecords} cost on account,
 * ledger, and idempotency writes that it then discards. The per-payment event count is small here, but on
 * a busy table this is a real cost. If server-side filtering matters, use Kinesis Data Streams for
 * DynamoDB, which supports consumer-side stream filters, instead of raw DynamoDB Streams.
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
     *
     * <p><strong>Why 100.</strong> DynamoDB Streams accepts up to {@code 1000} records per
     * {@code GetRecords} call, so this value is deliberately conservative. For this low-volume sample
     * a single payment produces only a handful of events, so {@code 100} drains a shard within one
     * poll tick while keeping each call small and predictable, and it lets the per-tick round cap
     * {@link #MAX_GET_RECORDS_ROUNDS_PER_SHARD} stay meaningful instead of being reached in a single
     * round. A production poller on a busy table would raise this toward {@code 1000} to drain hot
     * shards in fewer calls. Note that {@link #MAX_RETRY_COUNT_ENTRIES} sizing assumes this value.
     */
    private static final int POLL_LIMIT = 100;

    /**
     * Upper bound on {@code GetRecords} iterations per shard per scheduler tick so one hot shard cannot starve others.
     *
     * <p><strong>Why 512.</strong> The poller runs on a single-thread scheduler, so without a cap a
     * continuously busy shard could loop on {@code GetRecords} forever and never yield to other
     * shards. At {@link #POLL_LIMIT} (100) records per round, {@code 512} rounds drain up to about
     * fifty thousand records from one shard in a single tick, far above anything this sample
     * produces, while still guaranteeing the loop returns control to the scheduler so the remaining
     * shards are polled. It is a fairness backstop rather than a throughput target.
     */
    private static final int MAX_GET_RECORDS_ROUNDS_PER_SHARD = 512;

    /**
     * After this many processing failures for the same {@code paymentId}, the record is skipped so the shard advances.
     *
     * <p><strong>Why 3.</strong> Processing failures here are typically transient (a throttled
     * dependent write or a brief network blip), which a small number of retries clears. Three
     * attempts give a genuine poison pill enough chances to succeed on a recoverable error while
     * keeping a permanently failing record from blocking shard progress for long. The skip is logged
     * at {@code ERROR} so operators are alerted rather than the failure being silently swallowed.
     */
    public static final int MAX_PROCESS_RETRIES = 3;

    /**
     * Upper bound, in seconds, that {@link #stop} waits for the poller thread to finish its current pass before
     * returning. An in-flight {@code GetRecords} or processor call is itself bounded by the SDK {@code apiCallTimeout}
     * (see {@code DynamoDbConfig}), so this wait drains comfortably inside {@code spring.lifecycle.timeout-per-shutdown-phase}.
     */
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    /**
     * Upper bound on the number of {@code paymentId} entries kept in {@link #processingFailureCounts} before the
     * least-recently-touched one is evicted. Entries normally self-remove on success or after
     * {@link #MAX_PROCESS_RETRIES}, so the only entry that can linger is a payment that failed transiently (one or
     * two times, under the limit) and then never reappeared on the stream, an exceptional case rather than normal
     * flow.
     *
     * <p><strong>Why 1024.</strong> The poller reads at most {@link #POLL_LIMIT} (100) records per second per
     * shard. Even under a pessimistic sustained one-percent transient-failure-then-never-return rate (about one
     * lingering entry per second), 1024 entries cover roughly seventeen minutes of continuous pathological leakage
     * before the oldest is evicted, by which point the {@code ERROR} poison-pill logs would have alerted operators
     * many times over. It is therefore far above any healthy steady state for this low-volume sample while bounding
     * the map to a few hundred kilobytes, and a power of two so it maps cleanly onto the backing
     * {@link LinkedHashMap} capacity.
     */
    private static final int MAX_RETRY_COUNT_ENTRIES = 1024;

    /**
     * Prefix that namespaces reservation-release failure-count keys in {@link #processingFailureCounts} so they
     * never collide with the payment ids tracked by the {@code OUTBOUND_PAYMENT_CREATED} path.
     */
    private static final String RESERVATION_RELEASE_FAILURE_PREFIX = "release:";

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
    /**
     * Per-shard in-memory iterator and sequence checkpoints. Pruned after each {@link #discoverShards} pass via
     * {@code keySet().retainAll(activeShardIds)} so entries for closed shards no longer present on the stream do
     * not accumulate over the lifetime of a long-running pod.
     */
    private final ConcurrentHashMap<String, ShardCheckpoint> checkpoints = new ConcurrentHashMap<>();
    /**
     * Processing failure counts per stream work item for poison-pill handling. Keys are payment ids for the
     * {@code OUTBOUND_PAYMENT_CREATED} path and {@link #RESERVATION_RELEASE_FAILURE_PREFIX}-prefixed reservation
     * keys for the TTL release path. Entries self-remove on success or after {@link #MAX_PROCESS_RETRIES}. An
     * access-ordered LRU bounded at {@link #MAX_RETRY_COUNT_ENTRIES} evicts the least-recently-touched key so a
     * transient failure that never returns cannot grow the map without limit.
     */
    private final Map<String, Integer> processingFailureCounts = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                /**
                 * Evicts the least recently used payment id when the map exceeds {@code MAX_RETRY_COUNT_ENTRIES}.
                 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    return size() > MAX_RETRY_COUNT_ENTRIES;
                }
            });
    /** Single-thread scheduler that drives {@link #pollLoop}. */
    private ScheduledExecutorService scheduler;

    /**
     * @param dynamoDbClient      resolves the table name to a stream ARN
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
     * <p><strong>Graceful-shutdown contract.</strong> On a SIGTERM the application is configured for graceful
     * shutdown ({@code server.shutdown=graceful}, {@code spring.lifecycle.timeout-per-shutdown-phase}), so Spring
     * stops this {@link SmartLifecycle} bean before tearing the context down. This method flips {@link #running}
     * to false so the poll loop reschedules no further work, calls {@code shutdownNow} to interrupt the poller
     * thread, then waits up to {@link #SHUTDOWN_AWAIT_SECONDS} for it to finish its current pass.
     *
     * <p>A {@code CompletableFuture.join} on an in-flight {@code GetRecords} or processor call does not honor the
     * interrupt, but each such call is bounded by the SDK {@code apiCallTimeout} (see {@code DynamoDbConfig}), so the
     * in-flight call fails fast rather than pinning the poller until the operating-system socket timeout. The bounded
     * wait therefore drains the current pass well inside the configured shutdown window. If the wait elapses, a
     * warning is logged and shutdown proceeds.
     */
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("Stopping DynamoDB Streams poller: tableName={}", tableName);
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    if (!scheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                        logger.warn("DynamoDB Streams poller did not finish within {}s of shutdown; "
                                + "proceeding with context close: tableName={}", SHUTDOWN_AWAIT_SECONDS, tableName);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

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

    /**
     * Open shards for this poll cycle.
     *
     * <p>Pages {@code DescribeStream} with {@code exclusiveStartShardId} until
     * {@code lastEvaluatedShardId} is null or blank, accumulating shards from every page. DynamoDB
     * returns at most 100 shards per page, so a heavily resharded table can expose more shards than
     * a single page carries. Paging ensures shards beyond the first page are not skipped. A null or
     * blank {@code lastEvaluatedShardId} marks the last page and terminates the loop, so the poller
     * never re-requests the first page indefinitely.
     *
     * <p>On the success path, {@link #checkpoints} is pruned to the discovered shard ids
     * ({@code keySet().retainAll(activeShardIds)}) so entries for closed shards no longer present on
     * the stream are dropped. A describe failure returns an empty list without pruning, so a transient
     * error never wipes live checkpoints.
     */
    private List<Shard> discoverShards(String streamArn) {
        try {
            List<Shard> shards = new ArrayList<>();
            String exclusiveStartShardId = null;
            do {
                var response = streamsClient.describeStream(
                        DescribeStreamRequest.builder()
                                .streamArn(streamArn)
                                .exclusiveStartShardId(exclusiveStartShardId)
                                .build())
                        .join();
                shards.addAll(response.streamDescription().shards());
                exclusiveStartShardId = response.streamDescription().lastEvaluatedShardId();
            } while (exclusiveStartShardId != null && !exclusiveStartShardId.isBlank());

            pruneStaleCheckpoints(shards);
            return shards;
        } catch (Exception e) {
            logger.error("Failed to describe stream: streamArn={}", streamArn, e);
            return List.of();
        }
    }

    /**
     * Drops {@link #checkpoints} entries for shards that are no longer present in the freshly discovered
     * shard set, so closed-shard checkpoints do not accumulate. Called only on a successful discovery so a
     * transient describe failure never clears live checkpoints.
     */
    private void pruneStaleCheckpoints(List<Shard> activeShards) {
        Set<String> activeShardIds = new HashSet<>();
        for (Shard shard : activeShards) {
            activeShardIds.add(shard.shardId());
        }
        checkpoints.keySet().retainAll(activeShardIds);
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
     * Routes a stream record to the right handler.
     *
     * <p>{@code REMOVE} records are inspected for temporary reservation deletions and may trigger a reservation release
     * via {@link #processReservationExpiry(Record)}. {@code INSERT} records of {@link PaymentEvent#ENTITY_TYPE}
     * with {@link PaymentEventType#OUTBOUND_PAYMENT_CREATED} drive the payment processor. Every other record is
     * ignored.
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
        String eventName = streamRecord.eventName() != null ? streamRecord.eventName().toString() : null;

        if ("REMOVE".equals(eventName)) {
            processReservationExpiry(streamRecord);
            return;
        }

        if (!"INSERT".equals(eventName)) {
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
        logger.debug("Outbound payment created event detected on stream: paymentId={}", paymentId);

        try {
            processor.processPayment(paymentId).join();
            processingFailureCounts.remove(paymentId);
        } catch (Exception e) {
            int attempt = processingFailureCounts.merge(paymentId, 1, Integer::sum);
            if (attempt >= MAX_PROCESS_RETRIES) {
                logger.error("Outbound payment processing exhausted retries, skipping poison pill: "
                                + "paymentId={}, attemptCount={}",
                        paymentId, attempt, e);
                processingFailureCounts.remove(paymentId);
            } else {
                logger.warn("Outbound payment processing failed, will retry: paymentId={}, attempt={}/{}",
                        paymentId, attempt, MAX_PROCESS_RETRIES, e);
                throw e;
            }
        }
    }

    /**
     * Releases an abandoned hold when a {@code RESERVATION_TEMP#} temporary reservation row is removed.
     *
     * <p>A temporary reservation {@code REMOVE} arrives from one of two sources, and both are handled identically. Either the
     * complete transaction deleted the temporary reservation atomically while consuming the hold, or DynamoDB expired the
     * temporary reservation through its {@code ttl} attribute because the payment was abandoned. The handler parses the debtor
     * account id from the {@code PK} and the reservation id from the {@code SK} (the record keys are present on a
     * {@code REMOVE} regardless of the stream view type) and calls
     * {@link OutboundPaymentProcessor#releaseExpiredReservation(String, String)}. That call reads the audit
     * reservation and releases funds only when its status is still active, so a temporary reservation removed by a normal
     * completion finds a consumed audit row and is a safe no-op.
     *
     * <p>Failures reuse the same bounded poison-pill retry as the payment path, keyed under
     * {@link #RESERVATION_RELEASE_FAILURE_PREFIX} so the counters never collide with payment ids.
     *
     * @param streamRecord the {@code REMOVE} stream record to inspect
     */
    private void processReservationExpiry(Record streamRecord) {
        if (streamRecord.dynamodb() == null) {
            return;
        }
        Map<String, AttributeValue> keys = streamRecord.dynamodb().keys();
        if (keys == null) {
            return;
        }
        AttributeValue sortKeyAttr = keys.get("SK");
        if (sortKeyAttr == null || sortKeyAttr.s() == null
                || !sortKeyAttr.s().startsWith(Reservation.TEMPORARY_KEY_PREFIX)) {
            return;
        }
        AttributeValue partitionKeyAttr = keys.get("PK");
        if (partitionKeyAttr == null || partitionKeyAttr.s() == null) {
            return;
        }

        String accountId = stripPrefix(partitionKeyAttr.s(), Account.KEY_PREFIX);
        String reservationId = stripPrefix(sortKeyAttr.s(), Reservation.TEMPORARY_KEY_PREFIX);
        String failureKey = RESERVATION_RELEASE_FAILURE_PREFIX + accountId + "#" + reservationId;
        logger.debug("temporary reservation expired on stream: accountId={}, reservationId={}",
                accountId, reservationId);

        try {
            processor.releaseExpiredReservation(accountId, reservationId).join();
            processingFailureCounts.remove(failureKey);
        } catch (Exception e) {
            int attempt = processingFailureCounts.merge(failureKey, 1, Integer::sum);
            if (attempt >= MAX_PROCESS_RETRIES) {
                logger.error("Reservation release exhausted retries, skipping poison pill: "
                                + "accountId={}, reservationId={}, attemptCount={}",
                        accountId, reservationId, attempt, e);
                processingFailureCounts.remove(failureKey);
            } else {
                logger.warn("Reservation release failed, will retry: accountId={}, reservationId={}, attempt={}/{}",
                        accountId, reservationId, attempt, MAX_PROCESS_RETRIES, e);
                throw e;
            }
        }
    }

    /**
     * Strips a known key prefix from a DynamoDB key value, returning the remainder.
     *
     * @param value     full key attribute value such as {@code ACCOUNT#acc1} or {@code RESERVATION_TEMP#res_p1}
     * @param keyPrefix prefix to remove when present
     * @return the value with {@code keyPrefix} removed, or the original value when it does not start with the prefix
     */
    private static String stripPrefix(String value, String keyPrefix) {
        return value.startsWith(keyPrefix) ? value.substring(keyPrefix.length()) : value;
    }
}
