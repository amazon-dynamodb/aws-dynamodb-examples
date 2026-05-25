package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.LeaderboardEntry;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.LeaderboardRepository;
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
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsAsyncClient;

/**
 * Polls the GameEvents DynamoDB Stream for {@link GameEventType#PVP_MATCH} inserts and
 * writes corresponding leaderboard entries to the LeaderboardAggregate table.
 *
 * <p>A single-threaded {@link ScheduledExecutorService} drives shard discovery and record
 * consumption, with in-memory checkpoints and bounded retries.
 *
 * <p>Uses {@link SmartLifecycle} to start polling after the Spring context is fully
 * initialised and to stop gracefully on shutdown.
 *
 * <p>This is a simple, single-threaded poller. Production workloads typically use the Kinesis
 * Client Library (KCL) for shard management, checkpointing, and fault tolerance.
 *
 * <p>Shard iterator type is configurable via {@code dynamodb.streams.iterator-type} (default
 * {@link ShardIteratorType#LATEST}). Set {@link ShardIteratorType#TRIM_HORIZON} when you need
 * reads from the start of the stream retention window.
 *
 * <p><strong>Checkpointing:</strong> For each shard id, the poller keeps {@code nextShardIterator}
 * and the last seen {@code sequenceNumber} in memory. Across poll ticks it resumes with
 * {@code GetRecords(nextShardIterator)} instead of reopening the iterator from scratch.
 * Expired iterators are renewed with {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER}. If data
 * was trimmed (including on {@code GetRecords}), the checkpoint is reset and the poller falls
 * back to the configured iterator type. Checkpoints are not persisted across JVM restarts,
 * so a process restart may re-read the stream from the configured starting position.
 *
 * <p><strong>Poison-pill protection:</strong> If a record fails processing {@link #MAX_PROCESS_RETRIES}
 * times, it is skipped so the shard can make progress. The failure is logged at {@code ERROR}
 * for operational alerting.
 *
 * <p>{@link #MAX_GET_RECORDS_ROUNDS_PER_SHARD} caps {@code GetRecords} iterations per shard
 * per poll tick so one busy shard cannot starve the scheduler.
 *
 * <p>For each {@code PVP_MATCH} event, the listener writes one leaderboard row using
 * {@code playerScore} as the numeric score (no accumulation across multiple matches).
 */
@Component
public class DynamoDbStreamsLeaderboardListener implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbStreamsLeaderboardListener.class);

    /** Delay between poll loop invocations when healthy. */
    private static final long POLL_INTERVAL_MS = 1000;

    /** Poll for in-flight async work to finish after {@link #stop()} before closing SDK clients. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    /** Interval for checking whether an async call should be cancelled during shutdown. */
    private static final long ASYNC_JOIN_POLL_INTERVAL_MS = 500;

    /** Maximum records per {@code GetRecords} call. */
    private static final int POLL_LIMIT = 100;

    /** Safety cap on inner {@code GetRecords} loops per shard per tick. */
    private static final int MAX_GET_RECORDS_ROUNDS_PER_SHARD = 512;

    /**
     * After this many processing failures for the same record key, the record is skipped
     * so the shard advances.
     */
    public static final int MAX_PROCESS_RETRIES = 3;

    /** Default leaderboard scope written from stream-derived PVP scores. */
    public static final String DEFAULT_SCOPE = "SEASON#default#MODE#ranked";

    /** Resolves table metadata such as stream ARN. */
    private final DynamoDbAsyncClient dynamoDbClient;

    /** Reads shard iterators and stream records. */
    private final DynamoDbStreamsAsyncClient streamsClient;

    /** Persists projected leaderboard rows. */
    private final LeaderboardRepository leaderboardRepository;

    /** Physical GameEvents table name whose stream is consumed. */
    private final String gameEventsTableName;

    /** Cold-start iterator type from configuration. */
    private final ShardIteratorType shardIteratorType;

    /** Lifecycle flag for {@link SmartLifecycle}. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Per-shard iterator and sequence checkpoints (in-memory only). */
    private final ConcurrentHashMap<String, ShardCheckpoint> checkpoints = new ConcurrentHashMap<>();

    /** Counts failed processing attempts per logical record key for poison-pill handling. */
    private final ConcurrentHashMap<String, Integer> retryCounts = new ConcurrentHashMap<>();

    /** Single-thread scheduler that runs {@link #pollLoop}. */
    private ScheduledExecutorService scheduler;

    /**
     * Constructs the listener and parses the iterator type from configuration.
     *
     * @param dynamoDbClient        resolves table to stream ARN
     * @param streamsClient         shard iterators and records
     * @param leaderboardRepository writes leaderboard entries extracted from stream records
     * @param gameEventsTableName   the GameEvents table whose stream is consumed
     * @param iteratorTypeRaw       {@link ShardIteratorType#name()} for example {@code LATEST} (default)
     *                              or {@code TRIM_HORIZON}
     */
    public DynamoDbStreamsLeaderboardListener(DynamoDbAsyncClient dynamoDbClient,
                                             DynamoDbStreamsAsyncClient streamsClient,
                                             LeaderboardRepository leaderboardRepository,
                                             @Value("${dynamodb.game-events-table-name}") String gameEventsTableName,
                                             @Value("${dynamodb.streams.iterator-type:LATEST}") String iteratorTypeRaw) {
        this.dynamoDbClient = dynamoDbClient;
        this.streamsClient = streamsClient;
        this.leaderboardRepository = leaderboardRepository;
        this.gameEventsTableName = gameEventsTableName;
        this.shardIteratorType = parseShardIteratorType(iteratorTypeRaw);
        logger.info("DynamoDB Streams leaderboard listener initialized [tableName={}, iteratorType={}]",
                gameEventsTableName, this.shardIteratorType);
    }

    /**
     * Parses {@code dynamodb.streams.iterator-type} or returns {@link ShardIteratorType#LATEST}.
     *
     * @param raw property value from Spring
     * @return matching enum constant or LATEST when unknown
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
     * <p>Starts a daemon single-thread scheduler. The first poll is delayed so the table
     * and seed data exist.
     */
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("DynamoDB Streams leaderboard poller started [tableName={}]",
                    gameEventsTableName);
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "leaderboard-streams-poller");
                t.setDaemon(true);
                return t;
            });
            scheduler.schedule(this::pollLoop, 2, TimeUnit.SECONDS);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stops scheduling, interrupts an in-flight poll, and waits for the scheduler thread
     * so SDK clients can be closed safely during context shutdown.
     */
    @Override
    public void stop() {
        stopAndAwaitTermination();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Invokes the completion callback only after the poller thread has stopped so Spring
     * does not destroy {@link DynamoDbAsyncClient} beans while a poll is still in flight.
     */
    @Override
    public void stop(Runnable callback) {
        stopAndAwaitTermination();
        callback.run();
    }

    /**
     * Clears the running flag, cancels scheduled work, and waits for the poller thread.
     */
    private void stopAndAwaitTermination() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        logger.info("DynamoDB Streams leaderboard poller stopped");
        ScheduledExecutorService activeScheduler = scheduler;
        if (activeScheduler == null) {
            return;
        }
        activeScheduler.shutdownNow();
        try {
            if (!activeScheduler.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Streams poller scheduler did not terminate within {} seconds",
                        SHUTDOWN_AWAIT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            activeScheduler.shutdownNow();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} while the stream poller scheduler is active
     */
    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Runs after most other beans: ensures table initializers and repositories are ready
     * before polling.
     *
     * @return {@link Integer#MAX_VALUE} so start happens late in the context lifecycle
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * Describes the stream, walks each shard once, processes records, then reschedules itself.
     *
     * <p>Exceptions inside the loop are caught and logged so a transient failure does not
     * kill the scheduler thread.
     */
    private void pollLoop() {
        try {
            String streamArn = discoverStreamArn();
            if (streamArn == null) {
                logger.warn("DynamoDB stream ARN not found [tableName={}, "
                                + "hint=verifyStreamsEnabled=true]",
                        gameEventsTableName);
                scheduleNextPoll();
                return;
            }

            List<Shard> shards = discoverShards(streamArn);
            for (Shard shard : shards) {
                if (!running.get()) break;
                pollShard(streamArn, shard);
            }
        } catch (CancellationException e) {
            // Poller stopped while waiting on an async SDK call; exit quietly.
        } catch (Exception e) {
            if (!running.get()) {
                return;
            }
            logger.error("Leaderboard streams poll loop failed [tableName={}]",
                    gameEventsTableName, e);
        }

        scheduleNextPoll();
    }

    /**
     * Schedules the next {@link #pollLoop} tick when still running.
     */
    private void scheduleNextPoll() {
        if (running.get() && scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::pollLoop, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Loads the latest stream ARN for {@link #gameEventsTableName}.
     *
     * @return ARN or {@code null} when describe fails or stream disabled
     */
    private String discoverStreamArn() {
        try {
            var tableDesc = joinIfRunning(dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(gameEventsTableName).build()));
            return tableDesc.table().latestStreamArn();
        } catch (CancellationException e) {
            return null;
        } catch (Exception e) {
            if (running.get()) {
                logger.error("Failed to describe DynamoDB table for stream discovery [tableName={}]",
                        gameEventsTableName, e);
            }
            return null;
        }
    }

    /**
     * Lists open shards for the stream.
     *
     * @param streamArn stream to describe
     * @return shard list, possibly empty on error
     */
    private List<Shard> discoverShards(String streamArn) {
        try {
            var response = joinIfRunning(streamsClient.describeStream(
                    DescribeStreamRequest.builder().streamArn(streamArn).build()));
            return response.streamDescription().shards();
        } catch (CancellationException e) {
            return List.of();
        } catch (Exception e) {
            if (running.get()) {
                logger.error("Failed to describe DynamoDB stream [streamArn={}]",
                        streamArn, e);
            }
            return List.of();
        }
    }

    /**
     * Reads new records for one shard, advancing the in-memory checkpoint.
     *
     * <p>Cold start uses {@link #shardIteratorType}. Steady state uses {@code GetRecords} with the
     * saved {@code nextShardIterator}. Iterator expiry and trim are handled in
     * {@link #getRecordsWithRenewal}.
     *
     * @param streamArn the stream ARN that owns the shard
     * @param shard     the shard to consume
     */
    private void pollShard(String streamArn, Shard shard) {
        String shardId = shard.shardId();
        ShardCheckpoint checkpoint = checkpoints.computeIfAbsent(shardId, id -> new ShardCheckpoint());

        try {
            String iterator = checkpoint.nextIterator;
            if (iterator == null) {
                iterator = openShardIterator(streamArn, shardId, checkpoint);
                if (iterator == null) {
                    return;
                }
            }

            // Drain available records up to MAX_GET_RECORDS_ROUNDS_PER_SHARD to avoid starving others
            int rounds = 0;
            while (iterator != null && running.get() && rounds < MAX_GET_RECORDS_ROUNDS_PER_SHARD) {
                rounds++;
                GetRecordsResponse response = getRecordsWithRenewal(streamArn, shardId, checkpoint, iterator);

                for (Record streamRecord : response.records()) {
                    if (!running.get()) {
                        return;
                    }
                    processStreamRecord(streamRecord);
                    String seq = streamRecord.dynamodb() != null
                            ? streamRecord.dynamodb().sequenceNumber() : null;
                    if (seq != null && !seq.isBlank()) {
                        checkpoint.lastSequenceNumber = seq;
                    }
                }

                iterator = response.nextShardIterator();
                checkpoint.nextIterator = iterator;
            }
        } catch (Exception e) {
            logger.error("Failed to poll DynamoDB stream shard [shardId={}]", shardId, e);
        }
    }

    /**
     * Calls {@code GetRecords}. On {@link ExpiredIteratorException}, clears the iterator
     * and obtains a new one via {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER} when possible,
     * then retries once.
     *
     * <p>On {@link TrimmedDataAccessException} from {@code GetRecords} (iterator older than
     * the stream retention window), clears the checkpoint and opens a fresh iterator using
     * {@link #shardIteratorType} so polling recovers without logging a shard error every tick.
     *
     * @param streamArn the stream ARN for iterator renewal
     * @param shardId   the shard being consumed
     * @param checkpoint in-memory checkpoint holding iterator and sequence state
     * @param iterator  the current shard iterator to try first
     * @return the {@code GetRecords} response (possibly from a renewed iterator)
     */
    private GetRecordsResponse getRecordsWithRenewal(String streamArn,
                                                     String shardId,
                                                     ShardCheckpoint checkpoint,
                                                     String iterator) {
        try {
            return fetchRecords(iterator);
        } catch (CompletionException e) {
            // Distinguish transient iterator issues from permanent failures
            Throwable cause = e.getCause();
            if (cause instanceof ExpiredIteratorException) {
                logger.debug("Shard iterator expired and will be renewed [shardId={}, lastSequenceNumber={}]",
                        shardId, checkpoint.lastSequenceNumber != null ? checkpoint.lastSequenceNumber : "none");
                checkpoint.nextIterator = null;
                return fetchRecords(openShardIterator(streamArn, shardId, checkpoint));
            }
            if (cause instanceof TrimmedDataAccessException) {
                logger.warn("Stream read past trim horizon; reopening shard iterator [shardId={}, iteratorType={}]",
                        shardId, shardIteratorType);
                checkpoint.nextIterator = null;
                checkpoint.lastSequenceNumber = null;
                return fetchRecords(openShardIterator(streamArn, shardId, checkpoint));
            }
            throw e;
        }
    }

    /**
     * Performs one {@code GetRecords} call and blocks until the response is available.
     *
     * @param shardIterator the iterator to read from
     * @return records and next iterator from DynamoDB Streams
     */
    private GetRecordsResponse fetchRecords(String shardIterator) {
        return joinIfRunning(streamsClient.getRecords(
                        GetRecordsRequest.builder()
                                .shardIterator(shardIterator)
                                .limit(POLL_LIMIT)
                                .build()));
    }

    /**
     * Opens a shard iterator: {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER} when
     * {@link ShardCheckpoint#lastSequenceNumber} is set, otherwise {@link #shardIteratorType}.
     * If {@link TrimmedDataAccessException} occurs for {@code AFTER_SEQUENCE_NUMBER}, clears
     * the sequence and falls back to {@link #shardIteratorType}.
     *
     * @param streamArn the stream ARN that owns the shard
     * @param shardId   the shard to open an iterator for
     * @param checkpoint in-memory checkpoint (may be mutated on trim fallback)
     * @return the new shard iterator string
     */
    private String openShardIterator(String streamArn, String shardId, ShardCheckpoint checkpoint) {
        boolean useAfterSequence = checkpoint.lastSequenceNumber != null && !checkpoint.lastSequenceNumber.isBlank();

        GetShardIteratorRequest.Builder req = GetShardIteratorRequest.builder()
                .streamArn(streamArn)
                .shardId(shardId);

        if (useAfterSequence) {
            req.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    .sequenceNumber(checkpoint.lastSequenceNumber);
        } else {
            req.shardIteratorType(shardIteratorType);
        }

        try {
            String iterator = blockingShardIterator(req.build());
            checkpoint.nextIterator = iterator;
            return iterator;
        } catch (CompletionException e) {
            if (useAfterSequence && e.getCause() instanceof TrimmedDataAccessException) {
                logger.warn("Stream data trimmed before checkpoint sequence; reopening shard iterator "
                                + "[shardId={}, sequenceNumber={}, iteratorType={}]",
                        shardId, checkpoint.lastSequenceNumber, shardIteratorType);
                checkpoint.lastSequenceNumber = null;
                GetShardIteratorRequest fallback = GetShardIteratorRequest.builder()
                        .streamArn(streamArn)
                        .shardId(shardId)
                        .shardIteratorType(shardIteratorType)
                        .build();
                String reopened = blockingShardIterator(fallback);
                checkpoint.nextIterator = reopened;
                return reopened;
            }
            throw e;
        }
    }

    /**
     * Calls {@code GetShardIterator} and blocks until the iterator string is available.
     *
     * @param request the fully built iterator request
     * @return shard iterator token from DynamoDB Streams
     */
    private String blockingShardIterator(GetShardIteratorRequest request) {
        return joinIfRunning(streamsClient.getShardIterator(request)).shardIterator();
    }

    /**
     * In-memory checkpoint for one stream shard (not persisted across process restarts).
     *
     * <p>{@code nextIterator} holds the latest {@link GetRecordsResponse#nextShardIterator()}
     * for the next poll. {@code lastSequenceNumber} is the last processed sequence and is
     * used when renewing iterators with {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER}.
     */
    private static final class ShardCheckpoint {
        /** Latest {@link GetRecordsResponse#nextShardIterator()} for continued reads. */
        volatile String nextIterator;

        /** Last applied sequence for {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER} renewals. */
        volatile String lastSequenceNumber;
    }

    /**
     * Encapsulates the data extracted from a {@link GameEventType#PVP_MATCH} new image.
     *
     * @param playerId   internal player id from the stream record
     * @param playerName display name copied from the event or profile lookup
     * @param score      {@code playerScore} used as the leaderboard numeric score
     */
    private record PvpMatchOutcome(String playerId, String playerName, long score) {}

    /**
     * Filters to {@code INSERT} of {@link GameEvent#ENTITY_TYPE} with
     * {@link GameEventType#PVP_MATCH} and writes a leaderboard entry.
     *
     * <p><strong>Bounded retry:</strong> Processing failures are retried up to
     * {@link #MAX_PROCESS_RETRIES} times. While under the limit the exception is re-thrown,
     * causing {@link #pollShard} to stop iterating the current batch without advancing the
     * in-memory checkpoint. The next poll cycle resumes from the last successfully processed
     * sequence number. Once the limit is reached the record is treated as a poison pill.
     * The failure is logged at {@code ERROR} and the method returns normally so the checkpoint
     * can advance and the shard is unblocked.
     *
     * @param streamRecord the DynamoDB Streams record to evaluate and potentially project
     */
    public void processStreamRecord(Record streamRecord) {
        if (!isInsertEvent(streamRecord) || streamRecord.dynamodb() == null) {
            return;
        }

        Map<String, AttributeValue> newImage = streamRecord.dynamodb().newImage();
        if (!isPvpMatchImage(newImage)) {
            return;
        }

        Optional<PvpMatchOutcome> matchDataOpt = extractPvpMatchOutcome(newImage);
        if (matchDataOpt.isEmpty()) {
            return;
        }

        PvpMatchOutcome matchData = matchDataOpt.get();
        String recordKey = matchData.playerId() + "#" + streamRecord.dynamodb().sequenceNumber();
        logger.debug("PVP match stream record detected [eventType=PVP_MATCH, playerId={}, score={}]",
                matchData.playerId(), matchData.score());

        writeLeaderboardEntryWithRetry(buildLeaderboardEntry(matchData), recordKey);
    }

    /**
     * Checks whether the stream record represents an item creation.
     *
     * @param streamRecord the record to inspect
     * @return {@code true} when the stream record's event name is {@code INSERT}
     */
    private static boolean isInsertEvent(Record streamRecord) {
        return "INSERT".equals(streamRecord.eventName().toString());
    }

    /**
     * Validates that the new image belongs to a {@link GameEventType#PVP_MATCH} game event.
     *
     * @param newImage the {@code NEW_IMAGE} map from the stream record
     * @return {@code true} when entity type is {@code GAME_EVENT} and event type is {@code PVP_MATCH}
     */
    private static boolean isPvpMatchImage(Map<String, AttributeValue> newImage) {
        if (newImage == null) {
            return false;
        }
        AttributeValue entityTypeAttr = newImage.get("entityType");
        if (entityTypeAttr == null || !GameEvent.ENTITY_TYPE.equals(entityTypeAttr.s())) {
            return false;
        }
        AttributeValue eventTypeAttr = newImage.get("eventType");
        return eventTypeAttr != null && GameEventType.PVP_MATCH.name().equals(eventTypeAttr.s());
    }

    /**
     * Extracts the fields needed for a leaderboard entry from a validated PVP_MATCH new image.
     *
     * @param newImage the validated {@code NEW_IMAGE} map
     * @return populated {@link PvpMatchOutcome}, or empty when a required attribute is missing
     */
    private Optional<PvpMatchOutcome> extractPvpMatchOutcome(Map<String, AttributeValue> newImage) {
        AttributeValue playerIdAttr = newImage.get("playerId");
        if (playerIdAttr == null) {
            return Optional.empty();
        }

        AttributeValue playerScoreAttribute = newImage.get("playerScore");
        if (playerScoreAttribute == null || playerScoreAttribute.n() == null) {
            logger.warn("PVP match event missing score; skipping leaderboard write [eventType=PVP_MATCH, playerId={}]",
                    playerIdAttr.s());
            return Optional.empty();
        }

        String playerName = resolvePlayerName(newImage, playerIdAttr.s());
        long score = Long.parseLong(playerScoreAttribute.n());
        return Optional.of(new PvpMatchOutcome(playerIdAttr.s(), playerName, score));
    }

    /**
     * Returns the {@code playerName} attribute when present, or falls back to {@code defaultName}.
     *
     * @param newImage    the new image map from the stream record
     * @param defaultName fallback value (typically the player id)
     * @return resolved player name
     */
    private static String resolvePlayerName(Map<String, AttributeValue> newImage, String defaultName) {
        AttributeValue playerNameAttr = newImage.get("playerName");
        if (playerNameAttr != null && playerNameAttr.s() != null) {
            return playerNameAttr.s();
        }
        return defaultName;
    }

    /**
     * Builds a {@link LeaderboardEntry} from the extracted match data.
     *
     * @param matchData player id, name, and score extracted from the stream record
     * @return a fully populated entry ready for persistence
     */
    private static LeaderboardEntry buildLeaderboardEntry(PvpMatchOutcome matchData) {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPartitionKey(LeaderboardEntry.buildPartitionKey(DEFAULT_SCOPE));
        entry.setSortKey(LeaderboardEntry.buildSortKey(matchData.score(), matchData.playerId()));
        entry.setEntityType(LeaderboardEntry.ENTITY_TYPE);
        entry.setPlayerId(matchData.playerId());
        entry.setPlayerName(matchData.playerName());
        entry.setScore(matchData.score());
        entry.setLastUpdatedAt(Instant.now().toString());
        return entry;
    }

    /**
     * Writes a leaderboard entry to the repository, retrying up to {@link #MAX_PROCESS_RETRIES}
     * times. On exhaustion the record is treated as a poison pill and skipped.
     *
     * @param entry     the leaderboard row to persist
     * @param recordKey logical key for retry counting ({@code playerId#sequenceNumber})
     */
    private void writeLeaderboardEntryWithRetry(LeaderboardEntry entry, String recordKey) {
        try {
            joinIfRunning(leaderboardRepository.putLeaderboardEntry(entry));
            retryCounts.remove(recordKey);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            int attempt = retryCounts.merge(recordKey, 1, Integer::sum);
            if (attempt >= MAX_PROCESS_RETRIES) {
                logger.error("Leaderboard write failed after maximum retries; skipping poison record "
                                + "[playerId={}, attemptCount={}, maxAttempts={}]",
                        entry.getPlayerId(), attempt, MAX_PROCESS_RETRIES, e);
                retryCounts.remove(recordKey);
            } else {
                logger.warn("Leaderboard write failed and will be retried [playerId={}, attempt={}/{}, detail={}]",
                        entry.getPlayerId(), attempt, MAX_PROCESS_RETRIES, e.getMessage());
                throw e instanceof RuntimeException ? (RuntimeException) e : new CompletionException(e);
            }
        }
    }

    /**
     * Waits for an async SDK or repository call while the poller is still running.
     *
     * <p>During shutdown, pending work is cancelled instead of blocking on SDK retry backoff,
     * so Spring can close {@link DynamoDbAsyncClient} beans promptly. When the listener has
     * never been started, or has fully terminated, this method delegates to {@code join()}.
     *
     * @param future completion stage for the in-flight operation
     * @param <T>    response type
     * @return completed value
     * @throws CancellationException when the poller stops before the call completes
     */
    private <T> T joinIfRunning(CompletableFuture<T> future) {
        CompletableFuture<T> completableFuture = future.toCompletableFuture();
        if (!shouldCancelOnShutdown()) {
            return completableFuture.join();
        }
        while (running.get()) {
            try {
                return completableFuture.get(ASYNC_JOIN_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Keep polling running flag so shutdown can cancel long SDK retries quickly.
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new CompletionException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completableFuture.cancel(true);
                throw new CancellationException("Poller interrupted");
            }
        }
        completableFuture.cancel(true);
        throw new CancellationException("Poller stopped");
    }

    /**
     * Returns {@code true} when the poller has been started and not yet fully terminated,
     * meaning in-flight async work should be cancelled rather than blocking on SDK retries.
     */
    private boolean shouldCancelOnShutdown() {
        ScheduledExecutorService activeScheduler = scheduler;
        return activeScheduler != null && !activeScheduler.isTerminated();
    }
}
