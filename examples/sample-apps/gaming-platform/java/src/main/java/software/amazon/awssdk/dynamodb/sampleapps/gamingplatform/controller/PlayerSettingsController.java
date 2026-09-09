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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GetSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.UpdatePlayerSettingsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.PlayerSettingsService;

/**
 * REST controller for player settings (privacy, notifications, language).
 *
 * <p>Settings are stored in a separate {@code SK = SETTINGS} item under the same
 * partition as the player profile, so reads and writes here never conflict with
 * progression or purchase updates on the profile row.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code GET /api/v1/players/{playerId}/settings}: read settings</li>
 *   <li>{@code PATCH /api/v1/players/{playerId}/settings}: partial update with optimistic locking</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Player Settings", description = "Read and update player preference items")
public class PlayerSettingsController {

    private static final Logger logger = LoggerFactory.getLogger(PlayerSettingsController.class);

    /** Allowed character set for the {@code playerId} path variable. Length is bounded by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    /** Maximum accepted length for the {@code playerId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Settings read and update operations. */
    private final PlayerSettingsService playerSettingsService;

    /**
     * Constructs the controller.
     *
     * @param playerSettingsService settings business logic
     */
    public PlayerSettingsController(PlayerSettingsService playerSettingsService) {
        this.playerSettingsService = playerSettingsService;
    }

    /**
     * Retrieves the current settings for a player.
     *
     * @param playerId the target player
     * @return 200 OK with the settings, or 404 if the player does not exist
     */
    @Operation(
            summary = "Get player settings",
            description = """
                    Returns a player's notification, language, and visibility preferences. Loads \
                    the SETTINGS item on a separate sort key from the profile so preference reads \
                    do not contend with progression writes. Response is a slice with only the \
                    settings object.""")
    @ApiResponse(responseCode = "200", description = "Settings slice found",
            content = @Content(schema = @Schema(implementation = GetSettingsResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/players/{playerId}/settings")
    public CompletableFuture<ResponseEntity<GetSettingsResponse>> getSettings(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId) {
        logger.debug("Received get player settings request [playerId={}]", playerId);
        return playerSettingsService.getSettings(playerId).thenApply(ResponseEntity::ok);
    }

    /**
     * Applies a partial update to player settings with optimistic locking.
     *
     * @param playerId the target player
     * @param request  optional field overrides and the expected version
     * @return 200 OK with the updated settings, 404 if missing, or 409 on version conflict
     */
    @Operation(
            summary = "Update player settings",
            description = """
                    Updates one or more player preferences with a partial PATCH. Only non-null \
                    request fields are applied. Returns a settings-focused response with playerId and \
                    the updated settings. expectedVersion must match the current settings version. \
                    Valid profileVisibility values are PUBLIC, FRIENDS_ONLY, and PRIVATE.""")
    @ApiResponse(responseCode = "200", description = "Settings updated (settings slice)",
            content = @Content(schema = @Schema(implementation = UpdatePlayerSettingsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Player not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Optimistic lock conflict",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "Unexpected server error",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "DynamoDB throttled or temporarily unavailable, retry shortly",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PatchMapping("/players/{playerId}/settings")
    public CompletableFuture<ResponseEntity<UpdatePlayerSettingsResponse>> updateSettings(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId,
            @Valid @RequestBody UpdatePlayerSettingsRequest request) {
        logger.debug("Received update player settings request [playerId={}, expectedVersion={}]",
                playerId, request.expectedVersion());
        return playerSettingsService.updateSettings(playerId, request).thenApply(ResponseEntity::ok);
    }
}
