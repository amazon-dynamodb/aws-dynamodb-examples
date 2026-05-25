package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.LeaderboardResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.LeaderboardQueryService;

/**
 * REST controller for leaderboard queries.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/v1/leaderboards/{scope}}: top-N ranked entries for a scope</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Leaderboards", description = "Query ranked leaderboard aggregates")
public class LeaderboardController {

    private static final Logger logger = LoggerFactory.getLogger(LeaderboardController.class);

    /** Ranked leaderboard reads from the aggregate table. */
    private final LeaderboardQueryService leaderboardQueryService;

    /**
     * Creates the controller.
     *
     * @param leaderboardQueryService provides ranked leaderboard data
     */
    public LeaderboardController(LeaderboardQueryService leaderboardQueryService) {
        this.leaderboardQueryService = leaderboardQueryService;
    }

    /**
     * Retrieves the top-N leaderboard entries for the given scope.
     *
     * @param scope the leaderboard scope identifier (e.g. {@code SEASON#default#MODE#ranked})
     * @param limit maximum entries to return (default 10, clamped to [1, 100])
     * @return 200 OK with the ranked entries
     */
    @Operation(
            summary = "Get leaderboard",
            description = """
                    Returns the highest-scoring players for a season and game mode scope. Reads the \
                    top entries from the Leaderboard aggregate table. Entries are ranked by score \
                    descending. Default limit is 10. The limit is clamped to the range 1 through \
                    100. PVP_MATCH events from GameEvents maintain these aggregates asynchronously.""")
    @ApiResponse(responseCode = "200", description = "Leaderboard returned, may be empty",
            content = @Content(schema = @Schema(implementation = LeaderboardResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/leaderboards/{scope}")
    public ResponseEntity<LeaderboardResponse> getLeaderboard(
            @Parameter(description = "Leaderboard scope key, for example SEASON#default#MODE#ranked")
            @PathVariable String scope,
            @Parameter(description = "Maximum entries to return. Defaults to 10. Clamped to the range 1 through 100")
            @RequestParam(defaultValue = "10") int limit) {
        logger.debug("Received get leaderboard request [scope={}, limit={}]",
                scope, limit);
        return ResponseEntity.ok(leaderboardQueryService.getTopN(scope, limit));
    }
}
