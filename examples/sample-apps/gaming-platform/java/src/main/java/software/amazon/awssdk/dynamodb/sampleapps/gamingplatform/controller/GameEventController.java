package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

import java.util.concurrent.CompletableFuture;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.EventsPageResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.GameEventService;

/**
 * REST controller for recording game events and returning paginated event history.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/players/{playerId}/events}: append an event with TTL</li>
 *   <li>{@code GET /api/v1/players/{playerId}/events}: paginated event history</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Game Events", description = "Record append-only events and query paginated history")
public class GameEventController {

    private static final Logger logger = LoggerFactory.getLogger(GameEventController.class);

    /** Allowed character set for the {@code playerId} path variable. Length is bounded by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    /** Maximum accepted length for the {@code playerId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Appends events and pages the GameEvent table. */
    private final GameEventService gameEventService;

    /**
     * Creates the controller.
     *
     * @param gameEventService handles event recording and history queries
     */
    public GameEventController(GameEventService gameEventService) {
        this.gameEventService = gameEventService;
    }

    /**
     * Records a game event for a player with automatic TTL expiry.
     *
     * @param playerId the player who triggered the event
     * @param request  event type and event-specific attributes
     * @return 201 Created with the event id and timestamp
     */
    @Operation(
            summary = "Record game event",
            description = """
                    Appends a game activity event to the player's history. Verifies the player \
                    exists, then writes to the GameEvent table. Each event carries a TTL from \
                    dynamodb.game-events-ttl-seconds. Supported eventType values are PVP_MATCH, \
                    PURCHASE, LEVEL_PROGRESS, and CURRENCY_GRANT. Returns only eventId and \
                    recordedAt.""")
    @ApiResponse(responseCode = "201", description = "Event recorded",
            content = @Content(schema = @Schema(implementation = RecordEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/players/{playerId}/events")
    public CompletableFuture<ResponseEntity<RecordEventResponse>> recordEvent(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId,
            @Valid @RequestBody RecordEventRequest request) {
        logger.debug("Received record game event request [playerId={}, eventType={}]",
                playerId, request.eventType());
        return gameEventService.recordEvent(playerId, request)
                .thenApply(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * Retrieves paginated event history for a player.
     *
     * <p>Default order is newest first (DynamoDB {@code ScanIndexForward=false}). Pass
     * {@code scanIndexForward=true} for oldest first.
     *
     * @param playerId           the player whose events to query
     * @param limit              maximum events per page (default 20)
     * @param scanIndexForward   optional. {@code true} for ascending (oldest first), omit or {@code false} for descending (newest first)
     * @param nextToken          opaque pagination token from a previous events response
     * @return 200 OK with the events page ({@code events}) and optional {@code nextToken}
     */
    @Operation(
            summary = "List game events",
            description = """
                    Returns a paginated audit trail of game activity for a player. Default order is \
                    newest first. Pass scanIndexForward=true for oldest first. Page size defaults to \
                    20. The limit is clamped to the range 1 through 50. Pass nextToken from a \
                    previous response to continue pagination.""")
    @ApiResponse(responseCode = "200", description = "Events listed, may be empty",
            content = @Content(schema = @Schema(implementation = EventsPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid pagination token or validation error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/players/{playerId}/events")
    public CompletableFuture<ResponseEntity<EventsPageResponse>> getEvents(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId,
            @Parameter(description = "Maximum events per page. Defaults to 20. Clamped to the range 1 through 50")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "DynamoDB ScanIndexForward. true means oldest first. false or omitted means newest first")
            @RequestParam(required = false) Boolean scanIndexForward,
            @Parameter(description = "Opaque pagination token from the previous events response")
            @RequestParam(required = false) String nextToken) {
        logger.debug("Received list game events request [playerId={}, limit={}, scanIndexForward={}, hasNextToken={}]",
                playerId, limit, scanIndexForward, nextToken != null && !nextToken.isBlank());
        return gameEventService.getEvents(playerId, limit, scanIndexForward, nextToken)
                .thenApply(ResponseEntity::ok);
    }
}
