package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller;

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
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetProfileResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RegisterPlayerResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerProfileService;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerRegistrationService;

/**
 * REST controller for player registration and profile retrieval.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /api/v1/players}: register a player with idempotent replay</li>
 *   <li>{@code GET /api/v1/players/{playerId}/profile}: read profile slice</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Players", description = "Register players and read profile slices")
public class PlayerController {

    private static final Logger logger = LoggerFactory.getLogger(PlayerController.class);

    /** Allowed character set for the {@code playerId} path variable. Length is bounded by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    /** Maximum accepted length for the {@code playerId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Idempotent registration and replay handling. */
    private final PlayerRegistrationService playerRegistrationService;

    /** Profile reads by player id. */
    private final PlayerProfileService playerProfileService;

    /**
     * Creates the controller with its application services.
     *
     * @param playerRegistrationService handles registration with idempotent replay semantics
     * @param playerProfileService handles profile lookups
     */
    public PlayerController(PlayerRegistrationService playerRegistrationService,
                            PlayerProfileService playerProfileService) {
        this.playerRegistrationService = playerRegistrationService;
        this.playerProfileService = playerProfileService;
    }

    /**
     * Registers a new player or returns an existing profile on idempotent replay.
     *
     * <p>Idempotency is keyed by {@code platform} and {@code platformUserId}: the same
     * identity always maps to the same player id, so safe retries need no separate token.
     *
     * @return 201 Created for new registrations, 200 OK for replays
     */
    @Operation(
            summary = "Register player",
            description = """
                    Registers a new player for a cross-platform identity (platform and \
                    platformUserId). Atomically creates PROFILE, SETTINGS, and WALLET with \
                    TransactWriteItems. Returns a full snapshot with playerId, profile, wallet, \
                    settings, and created. Retries with the same platform and platformUserId \
                    return the existing snapshot with created=false. A conflicting platform \
                    identity for an existing player id is rejected. Valid platform values are PC, \
                    IOS, and ANDROID.""")
    @ApiResponse(responseCode = "201", description = "Player created with full snapshot",
            content = @Content(schema = @Schema(implementation = RegisterPlayerResponse.class)))
    @ApiResponse(responseCode = "200", description = "Idempotent replay with full snapshot",
            content = @Content(schema = @Schema(implementation = RegisterPlayerResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error (VALIDATION_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Platform identity conflict (PLAYER_ALREADY_EXISTS)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/players")
    public ResponseEntity<RegisterPlayerResponse> register(
            @Valid @RequestBody RegisterPlayerRequest request) {
        logger.debug("Received register player request [platform={}]", request.platform());
        RegisterPlayerResponse response = playerRegistrationService.registerPlayer(request);
        HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Retrieves the full profile for a player by id.
     *
     * @return 200 OK with the profile, or 404 if the player does not exist
     */
    @Operation(
            summary = "Get player profile",
            description = """
                    Returns a player's display name, platform, level, experience, and profile \
                    version. Loads the PROFILE item. Response is a slice with only the profile \
                    object. It has no playerId, wallet, or settings at the root.""")
    @ApiResponse(responseCode = "200", description = "Profile slice found",
            content = @Content(schema = @Schema(implementation = GetProfileResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player not found (PLAYER_NOT_FOUND)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Internal server error (INTERNAL_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/players/{playerId}/profile")
    public ResponseEntity<GetProfileResponse> getProfile(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId) {
        logger.debug("Received get player profile request [playerId={}]", playerId);
        return ResponseEntity.ok(playerProfileService.getProfile(playerId));
    }
}
