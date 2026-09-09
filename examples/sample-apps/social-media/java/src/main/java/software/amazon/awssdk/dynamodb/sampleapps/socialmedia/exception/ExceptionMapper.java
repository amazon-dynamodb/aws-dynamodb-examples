package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.dto.ErrorResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Maps dedicated domain exceptions and DynamoDB service faults to the JSON error envelope.
 *
 * <p>Lookup is by concrete class for the nineteen domain types. A DynamoDB subclass that has no
 * dedicated entry uses the {@link DynamoDbException} mapping. Types that are not registered return
 * empty so {@link GlobalExceptionHandler} can apply the generic {@code INTERNAL_ERROR} fallback.
 */
final class ExceptionMapper {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionMapper.class);

    private static final String VALIDATION_ERROR_CODE = "VALIDATION_ERROR";

    private static final String RETRY_AFTER_SECONDS = "1";

    private final Map<Class<? extends Throwable>, Function<Throwable, ResponseEntity<ErrorResponse>>> handlers;

    /**
     * Builds an immutable registry of domain and DynamoDB mappings.
     */
    ExceptionMapper() {
        Map<Class<? extends Throwable>, Function<Throwable, ResponseEntity<ErrorResponse>>> registry =
                new HashMap<>();
        put(registry, MissingActorException.class, this::mapMissingActor);
        put(registry, CannotFollowSelfException.class, this::mapCannotFollowSelf);
        put(registry, UserNotFoundException.class, this::mapUserNotFound);
        put(registry, AlreadyFollowingException.class, this::mapAlreadyFollowing);
        put(registry, AlreadyLikedException.class, this::mapAlreadyLiked);
        put(registry, PostNotFoundException.class, this::mapPostNotFound);
        put(registry, ConversationNotFoundException.class, this::mapConversationNotFound);
        put(registry, ConversationNotReadyException.class, this::mapConversationNotReady);
        put(registry, InvalidConversationTypeException.class, this::mapInvalidConversationType);
        put(registry, InvalidParticipantCountException.class, this::mapInvalidParticipantCount);
        put(registry, NotAParticipantException.class, this::mapNotAParticipant);
        put(registry, InvalidPaginationTokenException.class, this::mapInvalidPaginationToken);
        put(registry, ValidationException.class, this::mapValidation);
        put(registry, InvalidVisibilityException.class, this::mapInvalidVisibility);
        put(registry, InvalidMediaException.class, this::mapInvalidMedia);
        put(registry, InvalidPostTypeException.class, this::mapInvalidPostType);
        put(registry, MediaNotFoundException.class, this::mapMediaNotFound);
        put(registry, MediaStorageUnavailableException.class, this::mapMediaStorageUnavailable);
        put(registry, BatchWriteRetryExhaustedException.class, this::mapBatchWriteRetryExhausted);
        put(registry, ProvisionedThroughputExceededException.class, this::mapThroughputExceeded);
        put(registry, RequestLimitExceededException.class, this::mapRequestLimitExceeded);
        put(registry, ResourceNotFoundException.class, this::mapTableNotFound);
        put(registry, InternalServerErrorException.class, this::mapDynamoDbInternalError);
        put(registry, DynamoDbException.class, this::mapUnhandledDynamoDb);
        this.handlers = Map.copyOf(registry);
    }

    /**
     * Returns the mapped envelope when {@code throwable} is a registered domain type or a DynamoDB
     * service exception.
     *
     * @param throwable the unwrapped cause, or a DynamoDB fault thrown directly
     * @return mapped envelope, or empty when the type is not registered
     */
    Optional<ResponseEntity<ErrorResponse>> map(Throwable throwable) {
        Function<Throwable, ResponseEntity<ErrorResponse>> handler = handlers.get(throwable.getClass());
        if (handler != null) {
            return Optional.of(handler.apply(throwable));
        }
        if (throwable instanceof DynamoDbException) {
            return Optional.of(handlers.get(DynamoDbException.class).apply(throwable));
        }
        return Optional.empty();
    }

    /**
     * Registers a typed mapping so the registry can store a homogeneous {@link Function}.
     *
     * @param registry mutable registry being built
     * @param type     exception class to match
     * @param mapper   mapping for that class
     * @param <T>      exception type
     */
    private static <T extends Throwable> void put(
            Map<Class<? extends Throwable>, Function<Throwable, ResponseEntity<ErrorResponse>>> registry,
            Class<T> type,
            Function<T, ResponseEntity<ErrorResponse>> mapper) {
        registry.put(type, ex -> mapper.apply(type.cast(ex)));
    }

    /**
     * {@link MissingActorException} maps to HTTP 400 with {@code VALIDATION_ERROR}.
     *
     * @param ex missing or blank actor header
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapMissingActor(MissingActorException ex) {
        logger.warn("Missing actor header on a route that requires X-User-Id");
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR_CODE, ex.getMessage());
    }

    /**
     * {@link CannotFollowSelfException} maps to HTTP 400 with {@code CANNOT_FOLLOW_SELF}.
     *
     * @param ex self-follow attempt carrying the offending user id
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapCannotFollowSelf(CannotFollowSelfException ex) {
        logger.debug("Rejected self-follow [userId={}]", ex.getUserId());
        return body(HttpStatus.BAD_REQUEST, "CANNOT_FOLLOW_SELF", ex.getMessage());
    }

    /**
     * {@link UserNotFoundException} maps to HTTP 404 with {@code USER_NOT_FOUND}.
     *
     * @param ex missing-profile reference carrying the user id
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapUserNotFound(UserNotFoundException ex) {
        logger.debug("User not found [userId={}]", ex.getUserId());
        return body(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
    }

    /**
     * {@link AlreadyFollowingException} maps to HTTP 409 with {@code ALREADY_FOLLOWING}.
     *
     * @param ex duplicate-follow carrying the follower and followee ids
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapAlreadyFollowing(AlreadyFollowingException ex) {
        logger.debug("Already following [followerId={}, followeeId={}]",
                ex.getFollowerId(), ex.getFolloweeId());
        return body(HttpStatus.CONFLICT, "ALREADY_FOLLOWING", ex.getMessage());
    }

    /**
     * {@link AlreadyLikedException} maps to HTTP 409 with {@code ALREADY_LIKED}.
     *
     * @param ex duplicate-like carrying the liker and post ids
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapAlreadyLiked(AlreadyLikedException ex) {
        logger.debug("Already liked [userId={}, postId={}]", ex.getUserId(), ex.getPostId());
        return body(HttpStatus.CONFLICT, "ALREADY_LIKED", ex.getMessage());
    }

    /**
     * {@link PostNotFoundException} maps to HTTP 404 with {@code POST_NOT_FOUND}.
     *
     * @param ex missing or visibility-hidden post carrying the post id
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapPostNotFound(PostNotFoundException ex) {
        logger.debug("Post not found [postId={}]", ex.getPostId());
        return body(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", ex.getMessage());
    }

    /**
     * {@link ConversationNotFoundException} maps to HTTP 404 with {@code CONVERSATION_NOT_FOUND}.
     *
     * @param ex missing-conversation reference carrying the conversation id
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapConversationNotFound(ConversationNotFoundException ex) {
        logger.debug("Conversation not found [conversationId={}]", ex.getConversationId());
        return body(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", ex.getMessage());
    }

    /**
     * {@link ConversationNotReadyException} maps to HTTP 503 while a chunked create is incomplete.
     *
     * @param ex conversation with incomplete projection state
     * @return retryable error envelope
     */
    private ResponseEntity<ErrorResponse> mapConversationNotReady(ConversationNotReadyException ex) {
        logger.debug("Conversation not ready [conversationId={}]", ex.getConversationId());
        return throttled("CONVERSATION_NOT_READY", "Conversation creation is still completing. Retry shortly");
    }

    /**
     * {@link InvalidConversationTypeException} maps to HTTP 400 with {@code INVALID_CONVERSATION_TYPE}.
     *
     * @param ex conversation type rule breach
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidConversationType(InvalidConversationTypeException ex) {
        logger.debug("Invalid conversation type [reason={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "INVALID_CONVERSATION_TYPE", ex.getMessage());
    }

    /**
     * {@link InvalidParticipantCountException} maps to HTTP 400 with {@code INVALID_PARTICIPANT_COUNT}.
     *
     * @param ex participant count or distinctness rule breach
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidParticipantCount(InvalidParticipantCountException ex) {
        logger.debug("Invalid participant count [reason={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARTICIPANT_COUNT", ex.getMessage());
    }

    /**
     * {@link NotAParticipantException} maps to HTTP 400 with {@code NOT_A_PARTICIPANT}.
     *
     * @param ex sender is not a member of the target conversation
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapNotAParticipant(NotAParticipantException ex) {
        logger.debug("Not a participant [userId={}, conversationId={}]",
                ex.getUserId(), ex.getConversationId());
        return body(HttpStatus.BAD_REQUEST, "NOT_A_PARTICIPANT", ex.getMessage());
    }

    /**
     * {@link InvalidPaginationTokenException} maps to HTTP 400 with {@code INVALID_PAGINATION_TOKEN}.
     *
     * @param ex invalid opaque pagination token supplied by the client
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidPaginationToken(InvalidPaginationTokenException ex) {
        logger.warn("Invalid pagination token supplied");
        return body(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION_TOKEN", ex.getMessage());
    }

    /**
     * {@link ValidationException} maps to HTTP 400 with {@code VALIDATION_ERROR}.
     *
     * @param ex request-shape validation failure
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapValidation(ValidationException ex) {
        logger.warn("Validation error [details={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, VALIDATION_ERROR_CODE, ex.getMessage());
    }

    /**
     * {@link InvalidVisibilityException} maps to HTTP 400 with {@code INVALID_VISIBILITY}.
     *
     * @param ex visibility or allow-list rule breach
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidVisibility(InvalidVisibilityException ex) {
        logger.debug("Invalid visibility [reason={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "INVALID_VISIBILITY", ex.getMessage());
    }

    /**
     * {@link InvalidMediaException} maps to HTTP 400 with {@code INVALID_MEDIA}.
     *
     * @param ex media rule breach
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidMedia(InvalidMediaException ex) {
        logger.debug("Invalid media [reason={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", ex.getMessage());
    }

    /**
     * {@link InvalidPostTypeException} maps to HTTP 400 with {@code INVALID_POST_TYPE}.
     *
     * @param ex unrecognized post type
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapInvalidPostType(InvalidPostTypeException ex) {
        logger.debug("Invalid post type [reason={}]", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "INVALID_POST_TYPE", ex.getMessage());
    }

    /**
     * {@link MediaNotFoundException} maps to HTTP 404 with {@code MEDIA_NOT_FOUND}.
     *
     * @param ex referenced media object absent (upload not completed)
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapMediaNotFound(MediaNotFoundException ex) {
        logger.debug("Media not found [mediaId={}]", ex.getMediaId());
        return body(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", ex.getMessage());
    }

    /**
     * {@link MediaStorageUnavailableException} maps to HTTP 503 with {@code MEDIA_STORAGE_UNAVAILABLE}.
     *
     * @param ex transient object-store fault
     * @return 503 error envelope carrying {@code Retry-After}
     */
    private ResponseEntity<ErrorResponse> mapMediaStorageUnavailable(MediaStorageUnavailableException ex) {
        logger.warn("Media store temporarily unavailable", ex);
        return throttled("MEDIA_STORAGE_UNAVAILABLE",
                "Media object store is temporarily unavailable. Retry after a short delay");
    }

    /**
     * {@link BatchWriteRetryExhaustedException} maps to HTTP 503 because DynamoDB did not confirm every
     * requested fan-out write within the retry budget.
     *
     * @param ex exhausted batch-write retry operation
     * @return retryable error envelope
     */
    private ResponseEntity<ErrorResponse> mapBatchWriteRetryExhausted(BatchWriteRetryExhaustedException ex) {
        logger.warn("BatchWriteItem retries exhausted [unprocessedItemCount={}]]", ex.getUnprocessedItemCount());
        return throttled("BATCH_WRITE_RETRY_EXHAUSTED",
                "DynamoDB did not process every write. Retry after a short delay");
    }

    /**
     * {@link ProvisionedThroughputExceededException} maps to HTTP 503 with {@code THROUGHPUT_EXCEEDED}.
     *
     * @param ex DynamoDB throughput fault
     * @return retryable error envelope
     */
    private ResponseEntity<ErrorResponse> mapThroughputExceeded(ProvisionedThroughputExceededException ex) {
        logger.warn("DynamoDB provisioned throughput exceeded", ex);
        return throttled("THROUGHPUT_EXCEEDED",
                "Request rate exceeded provisioned throughput. Retry after a short delay");
    }

    /**
     * {@link RequestLimitExceededException} maps to HTTP 503 with {@code REQUEST_LIMIT_EXCEEDED}.
     *
     * @param ex DynamoDB account request-limit fault
     * @return retryable error envelope
     */
    private ResponseEntity<ErrorResponse> mapRequestLimitExceeded(RequestLimitExceededException ex) {
        logger.warn("DynamoDB request limit exceeded", ex);
        return throttled("REQUEST_LIMIT_EXCEEDED",
                "Account request limit exceeded. Retry after a short delay");
    }

    /**
     * {@link ResourceNotFoundException} maps to HTTP 503 with {@code TABLE_NOT_FOUND}.
     *
     * @param ex missing DynamoDB table or index
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapTableNotFound(ResourceNotFoundException ex) {
        logger.error("DynamoDB resource not found (table missing or being created?)", ex);
        return body(HttpStatus.SERVICE_UNAVAILABLE, "TABLE_NOT_FOUND",
                "A required DynamoDB resource is unavailable");
    }

    /**
     * {@link InternalServerErrorException} maps to HTTP 503 with {@code DYNAMODB_INTERNAL_ERROR}.
     *
     * @param ex DynamoDB internal fault
     * @return retryable error envelope
     */
    private ResponseEntity<ErrorResponse> mapDynamoDbInternalError(InternalServerErrorException ex) {
        logger.error("DynamoDB internal server error", ex);
        return throttled("DYNAMODB_INTERNAL_ERROR",
                "DynamoDB reported an internal error. Retry after a short delay");
    }

    /**
     * Unclassified {@link DynamoDbException} subclasses map to HTTP 500 with {@code INTERNAL_ERROR}
     * and no request reference in the message.
     *
     * @param ex DynamoDB service exception with no dedicated mapping
     * @return error envelope
     */
    private ResponseEntity<ErrorResponse> mapUnhandledDynamoDb(DynamoDbException ex) {
        logger.error("Unhandled DynamoDB error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    /**
     * Builds a JSON error envelope with the given status, code, and message.
     *
     * @param status  HTTP status
     * @param code    machine-readable error code
     * @param message human-readable description
     * @return error envelope
     */
    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, Instant.now()));
    }

    /**
     * Builds a 503 response with a {@code Retry-After} header for throttling and transient faults.
     *
     * @param code    machine-readable error code
     * @param message human-readable description
     * @return 503 error envelope carrying {@code Retry-After}
     */
    private static ResponseEntity<ErrorResponse> throttled(String code, String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(new ErrorResponse(code, message, Instant.now()));
    }
}
