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

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ErrorResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.ProgressionUpdateResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.ProgressionService;

/**
 * REST endpoint for player progression updates.
 *
 * <p>Accepts XP and currency deltas with an optimistic-locking version so that
 * concurrent updates are detected and the client can retry with a fresh read.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code PATCH /api/v1/players/{playerId}/progression}: apply XP delta with optimistic locking</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Progression", description = "Apply XP deltas with optimistic locking")
public class ProgressionController {

    private static final Logger logger = LoggerFactory.getLogger(ProgressionController.class);

    /** Allowed character set for the {@code playerId} path variable. Length is bounded by {@link #ID_MAX_LENGTH}. */
    static final String ID_PATTERN = "^[A-Za-z0-9_-]+$";

    /** Maximum accepted length for the {@code playerId} path variable. */
    static final int ID_MAX_LENGTH = 64;

    /** Optimistic-lock progression updates. */
    private final ProgressionService progressionService;

    /**
     * Constructs the controller.
     *
     * @param progressionService progression business logic
     */
    public ProgressionController(ProgressionService progressionService) {
        this.progressionService = progressionService;
    }

    /**
     * Applies an XP and currency delta to the player profile.
     *
     * @param playerId the target player
     * @param request  delta values and expected version for optimistic locking
     * @return the updated profile snapshot
     */
    @Operation(
            summary = "Update player progression",
            description = """
                    Applies an experience delta and recomputes level on the PROFILE item with \
                    optimistic locking. Returns a full snapshot with playerId, profile, wallet, \
                    settings, and appliedEventId. Currency changes use the purchases or wallet earn \
                    endpoints instead. A level-up triggers an automatic soft currency bonus. \
                    expectedVersion must match the current profile version.""")
    @ApiResponse(responseCode = "200", description = "Progression updated with full snapshot",
            content = @Content(schema = @Schema(implementation = ProgressionUpdateResponse.class)))
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
    @PatchMapping("/players/{playerId}/progression")
    public CompletableFuture<ResponseEntity<ProgressionUpdateResponse>> updateProgression(
            @Parameter(description = "Internal player id")
            @PathVariable
            @Size(max = ID_MAX_LENGTH)
            @Pattern(regexp = ID_PATTERN, message = "must match " + ID_PATTERN)
            String playerId,
            @Valid @RequestBody ProgressionUpdateRequest request) {
        logger.debug("Received progression update request [playerId={}, xpDelta={}, expectedVersion={}]",
                playerId, request.xpDelta(), request.expectedVersion());
        return progressionService.updateProgression(playerId, request).thenApply(ResponseEntity::ok);
    }
}
