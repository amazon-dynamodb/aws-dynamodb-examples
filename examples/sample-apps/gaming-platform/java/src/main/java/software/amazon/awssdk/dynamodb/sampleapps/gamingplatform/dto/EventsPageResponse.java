package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Paginated response for game event history.
 *
 * @param events    game events for the current page, newest first
 * @param nextToken opaque pagination token for the next page, or {@code null} or blank when no further page exists
 */
public record EventsPageResponse(List<GameEventDto> events,
                                 @JsonInclude(JsonInclude.Include.NON_EMPTY) String nextToken) {

    /**
     * Copies the events list into an unmodifiable list for this response.
     *
     * @param events    events for this page
     * @param nextToken pagination token, or {@code null} or blank when omitted from JSON
     */
    public EventsPageResponse {
        events = List.copyOf(events);
        if (nextToken != null && nextToken.isBlank()) {
            nextToken = null;
        }
    }
}
