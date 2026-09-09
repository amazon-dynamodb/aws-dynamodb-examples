package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.FollowerFanoutCap;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowerEdge;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.FollowingEdge;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.model.UserProfile;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.EnhancedQueryCollector;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * High-level {@link UserGraphRepository} using {@link DynamoDbEnhancedAsyncClient} with annotated
 * beans. The follow write is a two-edge {@code TransactWriteItems} built by
 * {@link FollowTransaction}, so it drops to the underlying low-level client. Selected when
 * {@code dynamodb.client-type=high-level}.
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.client-type", havingValue = "high-level")
public class HighLevelDynamoDbUserGraphRepository implements UserGraphRepository {

    private static final Logger logger = LoggerFactory.getLogger(HighLevelDynamoDbUserGraphRepository.class);

private static final Expression PROFILE_ABSENT = Expression.builder()
            .expression("attribute_not_exists(PK)")
            .build();

    private final DynamoDbAsyncClient lowLevelClient;
    private final DynamoDbAsyncTable<UserProfile> profileTable;
    private final DynamoDbAsyncTable<FollowingEdge> followingTable;
    private final DynamoDbAsyncTable<FollowerEdge> followerTable;
    private final FollowerEnumerationLimiter enumerationLimiter;
    private final String tableName;
    private final int followerFanoutCap;

    /**
     * @param enhancedClient      enhanced async client
     * @param tableName           configured UserGraph table name
     * @param followerFanoutCap   maximum follower edges to return for {@code PUBLIC} fan-out
     * @param enumerationLimiter  process-local Query rate limit for follower partitions
     */
    public HighLevelDynamoDbUserGraphRepository(
            DynamoDbEnhancedAsyncClient enhancedClient,
            @Value("${dynamodb.table-name.user-graph}") String tableName,
            FollowerFanoutCap followerFanoutCap,
            FollowerEnumerationLimiter enumerationLimiter) {
        this.lowLevelClient = enhancedClient.dynamoDbAsyncClient();
        this.tableName = tableName;
        this.profileTable = enhancedClient.table(tableName, TableSchema.fromBean(UserProfile.class));
        this.followingTable = enhancedClient.table(tableName, TableSchema.fromBean(FollowingEdge.class));
        this.followerTable = enhancedClient.table(tableName, TableSchema.fromBean(FollowerEdge.class));
        this.enumerationLimiter = enumerationLimiter;
        this.followerFanoutCap = followerFanoutCap.value();
        logger.info("Initialized high-level UserGraph repository [tableName={}]", tableName);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> putProfileIfAbsent(UserProfile profile) {
        return profileTable.putItem(PutItemEnhancedRequest.builder(UserProfile.class)
                .item(profile)
                .conditionExpression(PROFILE_ABSENT)
                .build());
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<UserProfile> getProfile(String userId) {
        Key key = Key.builder()
                .partitionValue(UserProfile.partitionKey(userId))
                .sortValue(UserProfile.SORT_KEY)
                .build();
        return profileTable.getItem(r -> r.key(key).consistentRead(true));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<Void> followTransaction(FollowingEdge following, FollowerEdge follower) {
        return FollowTransaction.put(lowLevelClient, tableName, following, follower);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<FollowingEdge> getFollowingEdge(String followerId, String followeeId) {
        Key key = Key.builder()
                .partitionValue(FollowingEdge.PK_PREFIX + followerId)
                .sortValue(FollowingEdge.sortKey(followeeId))
                .build();
        return followingTable.getItem(r -> r.key(key).consistentRead(true));
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<FollowerQueryResult> queryFollowers(String followeeId) {
        return enumerationLimiter.queryOrRateLimit(logger, followeeId, followerFanoutCap,
                () -> queryFollowersWithinCap(followeeId));
    }

    /**
     * Reads follower edges up to {@code dynamodb.follower-fanout-cap} after a rate-limit permit is
     * taken.
     *
     * @param followeeId creator whose followers are resolved
     * @return the bounded follower page
     */
    private CompletableFuture<FollowerQueryResult> queryFollowersWithinCap(String followeeId) {
        QueryConditional conditional = QueryConditional.sortBeginsWith(Key.builder()
                .partitionValue(FollowerEdge.PK_PREFIX + followeeId)
                .sortValue(FollowerEdge.SK_PREFIX)
                .build());
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(conditional)
                .limit(followerFanoutCap + 1)
                .build();
        return EnhancedQueryCollector.collectUpTo(followerTable.query(request), followerFanoutCap)
                .thenApply(bounded -> new FollowerQueryResult(
                        bounded.items(), bounded.complete(), followerFanoutCap));
    }
}
