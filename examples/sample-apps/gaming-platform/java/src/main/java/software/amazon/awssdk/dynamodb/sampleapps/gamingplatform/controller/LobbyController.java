package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LobbySummariesResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PlatformPlayersResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LobbyService;

/**
 * REST controller for lobby batch summaries and platform player browse via
 * {@code GSI_PLATFORM_PLAYERS}.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/lobbies/summaries}: batch load player summaries</li>
 *   <li>{@code GET /api/v1/lobbies/platform/{platform}}: browse players on a platform</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Lobby", description = "Batch player summaries and platform browse queries")
public class LobbyController {

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);

    /** Batch reads and platform-scoped queries. */
    private final LobbyService lobbyService;

    /**
     * Creates the controller.
     *
     * @param lobbyService handles batch lookups and platform queries
     */
    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    /**
     * Batch-loads player summaries for a lobby.
     *
     * @return 200 OK with summaries and any missing player ids
     */
    @Operation(
            summary = "Batch get lobby summaries",
            description = """
                    Loads lightweight player cards (name, level, last active) for a lobby or friend \
                    list. Uses BatchGetItem on PROFILE rows for up to 100 player ids. Found \
                    profiles are returned as summaries. Missing ids are listed in missingPlayerIds \
                    so callers can merge partial success with the request list.""")
    @ApiResponse(responseCode = "200", description = "Partial or full success",
            content = @Content(schema = @Schema(implementation = LobbySummariesResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (VALIDATION_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/lobbies/summaries")
    public ResponseEntity<LobbySummariesResponse> getLobbySummaries(
            @Valid @RequestBody LobbySummariesRequest request) {
        logger.debug("Received lobby summaries request [playerIdCount={}]",
                request.playerIds().size());
        return ResponseEntity.ok(lobbyService.getLobbySummaries(request));
    }

    /**
     * Browses players on a platform, ordered by most recently active first.
     *
     * @param platform the platform to filter by (PC, IOS, ANDROID)
     * @param limit    maximum number of results (default 20, clamped to [1, 50])
     * @return 200 OK with player summaries
     */
    @Operation(
            summary = "Browse players by platform",
            description = """
                    Lists recently active players on a platform using GSI_PLATFORM_PLAYERS. Ordered \
                    by most recent activity first. Valid platform values are PC, IOS, and ANDROID. \
                    Default limit is 20. The limit is clamped to the range 1 through 50.""")
    @ApiResponse(responseCode = "200", description = "Players listed, may be empty",
            content = @Content(schema = @Schema(implementation = PlatformPlayersResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid platform value (INVALID_ARGUMENT)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/lobbies/platform/{platform}")
    public ResponseEntity<PlatformPlayersResponse> getPlayersByPlatform(
            @Parameter(description = "Platform filter. Valid values are PC, IOS, and ANDROID")
            @PathVariable String platform,
            @Parameter(description = "Maximum results. Defaults to 20. Clamped to the range 1 through 50")
            @RequestParam(defaultValue = "20") int limit) {
        logger.debug("Received browse platform players request [platform={}, limit={}]",
                platform, limit);
        return ResponseEntity.ok(lobbyService.getPlayersByPlatform(platform, limit));
    }
}
