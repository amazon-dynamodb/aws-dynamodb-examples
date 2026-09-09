package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating player progression (XP only).
 *
 * <p>Currency changes are handled separately through purchases or wallet endpoints,
 * keeping progression writes isolated from wallet writes.
 *
 * @param xpDelta         experience points to add (may be negative if allowed)
 * @param reason          optional reason (e.g. LEVEL_COMPLETE, REWARD)
 * @param expectedVersion required for optimistic locking on the profile item
 */
public record ProgressionUpdateRequest(
        long xpDelta,
        String reason,
        @NotNull Long expectedVersion) {
}
