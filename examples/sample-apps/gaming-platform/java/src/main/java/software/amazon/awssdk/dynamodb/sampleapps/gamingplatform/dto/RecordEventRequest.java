package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for recording a game event.
 *
 * @param eventType       event type constant such as PVP_MATCH or PURCHASE
 * @param eventAttributes event-specific fields as a free-form map
 */
public record RecordEventRequest(
        @NotBlank String eventType,
        @NotNull Map<String, Object> eventAttributes) {
}
