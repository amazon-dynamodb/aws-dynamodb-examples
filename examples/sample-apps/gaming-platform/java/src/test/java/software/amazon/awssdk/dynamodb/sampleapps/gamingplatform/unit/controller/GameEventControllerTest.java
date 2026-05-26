package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.controller;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.GameEventController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.EventsPageResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GameEventDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.GameEventService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link GameEventController} using {@link WebMvcTest}.
 */
@Tag("unit")
@WebMvcTest(GameEventController.class)
class GameEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GameEventService gameEventService;

    @Test
    void recordEvent_whenValidRequest_shouldReturn201() throws Exception {
        RecordEventResponse response = new RecordEventResponse("evt-123", "2026-04-07T10:00:00Z");

        when(gameEventService.recordEvent(eq("player-1"), any(RecordEventRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/players/player-1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PVP_MATCH",
                                  "eventAttributes": {
                                    "matchId": "match-42",
                                    "result": "WIN"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-123"))
                .andExpect(jsonPath("$.recordedAt").value("2026-04-07T10:00:00Z"));
    }

    @Test
    void recordEvent_whenPlayerNotFound_shouldReturn404() throws Exception {
        when(gameEventService.recordEvent(eq("unknown"), any(RecordEventRequest.class)))
                .thenThrow(new PlayerNotFoundException("unknown"));

        mockMvc.perform(post("/api/v1/players/unknown/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PVP_MATCH",
                                  "eventAttributes": {}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void recordEvent_whenEventTypeMissing_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/players/player-1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventAttributes": {
                                    "matchId": "match-42"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void listEvents_whenEventsExist_shouldReturn200WithPage() throws Exception {
        GameEventDto dto = new GameEventDto("evt-1", "PVP_MATCH", "2026-04-07T10:00:00Z",
                Map.of("matchId", "match-42", "result", "WIN"));
        EventsPageResponse response = new EventsPageResponse(List.of(dto), "next-page-token");

        when(gameEventService.getEvents(eq("player-1"), eq(20), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/players/player-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.events[0].eventType").value("PVP_MATCH"))
                .andExpect(jsonPath("$.nextToken").value("next-page-token"));
    }

    @Test
    void listEvents_whenPlayerNotFound_shouldReturn404() throws Exception {
        when(gameEventService.getEvents(eq("unknown"), eq(20), isNull(), isNull()))
                .thenThrow(new PlayerNotFoundException("unknown"));

        mockMvc.perform(get("/api/v1/players/unknown/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void listEvents_whenPaginationTokenInvalid_shouldReturn400() throws Exception {
        when(gameEventService.getEvents(eq("player-1"), eq(10), isNull(), eq("bad-token")))
                .thenThrow(new InvalidPaginationTokenException("bad-token"));

        mockMvc.perform(get("/api/v1/players/player-1/events")
                        .param("limit", "10")
                        .param("nextToken", "bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listEvents_whenScanIndexForwardProvided_shouldPassQueryParam() throws Exception {
        EventsPageResponse response = new EventsPageResponse(List.of(), null);
        when(gameEventService.getEvents(eq("player-1"), eq(15), eq(Boolean.TRUE), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/players/player-1/events")
                        .param("limit", "15")
                        .param("scanIndexForward", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    void listEvents_whenNoNextPage_shouldOmitEmptyNextToken() throws Exception {
        GameEventDto dto = new GameEventDto("evt-1", "LOGIN", "2026-04-07T10:00:00Z", Map.of());
        EventsPageResponse response = new EventsPageResponse(List.of(dto), "");

        when(gameEventService.getEvents(eq("player-1"), eq(20), isNull(), isNull()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/players/player-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }
}
