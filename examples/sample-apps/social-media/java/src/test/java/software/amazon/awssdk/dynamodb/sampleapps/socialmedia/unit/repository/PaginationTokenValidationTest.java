package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.InboxPage;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.LowLevelDynamoDbConversationRepository;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.LowLevelDynamoDbTimelineRepository;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.repository.TimelinePage;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.PaginationTokenCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Unit coverage for route-specific, complete, path-user-bound pagination keys, explicit
 * merged-inbox branch state, and page-depth increment when a continuation token is minted.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PaginationTokenValidationTest {

    private static final String TIMELINES_TABLE = "JavaTimelines";
    private static final String CONVERSATIONS_TABLE = "JavaConversations";
    private static final String USER_ID = "user_1";
    private static final String OTHER_USER_ID = "user_2";

    @Mock
    private DynamoDbAsyncClient client;

    @Test
    void queryTimelineWithIncompleteContinuationKeyRejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of("timelinePostId", AttributeValue.fromS("post_1")));
        LowLevelDynamoDbTimelineRepository repository = new LowLevelDynamoDbTimelineRepository(client, TIMELINES_TABLE);

        assertThatThrownBy(() -> repository.queryTimeline(USER_ID, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInboxWithIncompleteFilteredContinuationKeyRejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of("conversationType", AttributeValue.fromS("GROUP")));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, "GROUP", 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryTimeline_whenContinuationKeyBelongsToAnotherUser_rejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(completeTimelineKey(OTHER_USER_ID));
        LowLevelDynamoDbTimelineRepository repository = new LowLevelDynamoDbTimelineRepository(client, TIMELINES_TABLE);

        assertThatThrownBy(() -> repository.queryTimeline(USER_ID, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInbox_whenFilteredContinuationKeyBelongsToAnotherUser_rejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(completeInboxKey(OTHER_USER_ID));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, "GROUP", 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInbox_whenMergedTokenOmitsOwner_rejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of(
                "inboxMerged", AttributeValue.fromS("1"),
                "directState", AttributeValue.fromS("START"),
                "groupState", AttributeValue.fromS("EXHAUSTED")));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, null, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInbox_whenMergedTokenOwnerDoesNotMatchPathUser_rejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of(
                "inboxMerged", AttributeValue.fromS("1"),
                "inboxUserId", AttributeValue.fromS(OTHER_USER_ID),
                "directState", AttributeValue.fromS("START"),
                "groupState", AttributeValue.fromS("START")));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, null, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInboxWithExhaustedMergedBranchDoesNotRestartThatBranch() {
        String token = PaginationTokenCodec.encode(Map.of(
                "inboxMerged", AttributeValue.fromS("1"),
                "inboxUserId", AttributeValue.fromS(USER_ID),
                "directState", AttributeValue.fromS("EXHAUSTED"),
                "groupState", AttributeValue.fromS("START")));
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).build()));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        InboxPage page = repository.queryInbox(USER_ID, null, 10, false, token).join();

        assertThat(page.items()).isEmpty();
        assertThat(page.nextToken()).isNull();
        verify(client, times(1)).query(any(QueryRequest.class));
    }

    @Test
    void queryInboxWithUnexpectedMergedTokenAttributeRejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of(
                "inboxMerged", AttributeValue.fromS("1"),
                "directState", AttributeValue.fromS("START"),
                "groupState", AttributeValue.fromS("START"),
                "timelinePostId", AttributeValue.fromS("post_1")));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, null, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryInboxWithInvalidMergedTokenMarkerRejectsTokenBeforeQuery() {
        String token = PaginationTokenCodec.encode(Map.of(
                "inboxMerged", AttributeValue.fromS("wrong-route"),
                "directState", AttributeValue.fromS("START"),
                "groupState", AttributeValue.fromS("START")));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        assertThatThrownBy(() -> repository.queryInbox(USER_ID, null, 10, false, token))
                .isInstanceOf(InvalidPaginationTokenException.class);
        verify(client, times(0)).query(any(QueryRequest.class));
    }

    @Test
    void queryTimeline_whenFirstPageHasContinuation_encodesDepthOne() {
        Map<String, AttributeValue> lastKey = completeTimelineKey();
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbTimelineRepository repository = new LowLevelDynamoDbTimelineRepository(client, TIMELINES_TABLE);

        TimelinePage page = repository.queryTimeline(USER_ID, 10, false, null).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(1);
        assertThat(PaginationTokenCodec.decode(page.nextToken())).isEqualTo(lastKey);
    }

    @Test
    void queryTimeline_whenIncomingTokenHasDepthOne_encodesDepthTwo() {
        Map<String, AttributeValue> lastKey = completeTimelineKey();
        String incoming = PaginationTokenCodec.encode(lastKey);
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbTimelineRepository repository = new LowLevelDynamoDbTimelineRepository(client, TIMELINES_TABLE);

        TimelinePage page = repository.queryTimeline(USER_ID, 10, false, incoming).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(2);
        assertThat(PaginationTokenCodec.decode(page.nextToken())).isEqualTo(lastKey);
    }

    @Test
    void queryInbox_whenFilteredFirstPageHasContinuation_encodesDepthOne() {
        Map<String, AttributeValue> lastKey = completeInboxKey();
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        InboxPage page = repository.queryInbox(USER_ID, "GROUP", 10, false, null).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(1);
        assertThat(PaginationTokenCodec.decode(page.nextToken())).isEqualTo(lastKey);
    }

    @Test
    void queryInbox_whenFilteredIncomingTokenHasDepthOne_encodesDepthTwo() {
        Map<String, AttributeValue> lastKey = completeInboxKey();
        String incoming = PaginationTokenCodec.encode(lastKey);
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        InboxPage page = repository.queryInbox(USER_ID, "GROUP", 10, false, incoming).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(2);
        assertThat(PaginationTokenCodec.decode(page.nextToken())).isEqualTo(lastKey);
    }

    @Test
    void queryInbox_whenMergedFirstPageHasContinuation_encodesDepthOne() {
        Map<String, AttributeValue> lastKey = completeInboxKey();
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);

        InboxPage page = repository.queryInbox(USER_ID, null, 10, false, null).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(1);
        assertThat(PaginationTokenCodec.decode(page.nextToken()))
                .containsKeys("inboxMerged", "inboxUserId", "directState", "groupState");
        assertThat(PaginationTokenCodec.decode(page.nextToken()).get("inboxUserId").s()).isEqualTo(USER_ID);
    }

    @Test
    void queryInbox_whenMergedIncomingTokenHasDepthOne_encodesDepthTwo() {
        Map<String, AttributeValue> lastKey = completeInboxKey();
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of()).lastEvaluatedKey(lastKey).build()));
        LowLevelDynamoDbConversationRepository repository =
                new LowLevelDynamoDbConversationRepository(client, CONVERSATIONS_TABLE);
        String incoming = repository.queryInbox(USER_ID, null, 10, false, null).join().nextToken();

        InboxPage page = repository.queryInbox(USER_ID, null, 10, false, incoming).join();

        assertThat(PaginationTokenCodec.depth(page.nextToken())).isEqualTo(2);
    }

    /**
     * Returns a complete {@code GSI_TIMELINE} continuation key for {@link #USER_ID}.
     *
     * @return timeline exclusive start key
     */
    private static Map<String, AttributeValue> completeTimelineKey() {
        return completeTimelineKey(USER_ID);
    }

    /**
     * Returns a complete {@code GSI_TIMELINE} continuation key for the given owner.
     *
     * @param userId timeline owner stored in the continuation key
     * @return timeline exclusive start key
     */
    private static Map<String, AttributeValue> completeTimelineKey(String userId) {
        return Map.of(
                "PK", AttributeValue.fromS("TIMELINE#" + userId),
                "SK", AttributeValue.fromS("TIMESTAMP#2026-01-01T00:00:00Z#POST#post_1"),
                "timelineUserId", AttributeValue.fromS(userId),
                "timelineCreatedAt", AttributeValue.fromS("2026-01-01T00:00:00Z"),
                "timelinePostId", AttributeValue.fromS("post_1"));
    }

    /**
     * Returns a complete {@code GSI_INBOX} continuation key for {@link #USER_ID}.
     *
     * @return inbox exclusive start key
     */
    private static Map<String, AttributeValue> completeInboxKey() {
        return completeInboxKey(USER_ID);
    }

    /**
     * Returns a complete {@code GSI_INBOX} continuation key for the given owner.
     *
     * @param userId inbox owner stored in the continuation key
     * @return inbox exclusive start key
     */
    private static Map<String, AttributeValue> completeInboxKey(String userId) {
        return Map.of(
                "PK", AttributeValue.fromS("USER#" + userId),
                "SK", AttributeValue.fromS("INBOX#conv_1"),
                "inboxUserId", AttributeValue.fromS(userId),
                "conversationType", AttributeValue.fromS("GROUP"),
                "lastActivityAt", AttributeValue.fromS("2026-01-01T00:00:00Z"));
    }
}
