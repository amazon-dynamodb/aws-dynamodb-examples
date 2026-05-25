package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.EventsPageResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.GameEventDto;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.PlayerProfile;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventPage;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.GameEventRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.repository.PlayerStateRepository;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.service.GameEventService;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Unit tests for {@link GameEventService}.
 *
 * <p>Uses mocked repositories and mappers to verify event recording, player validation, listing,
 * and pagination delegation.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class GameEventServiceTest {

    private static final String PLAYER_ID = "player-evt-001";

    @Mock
    private GameEventRepository gameEventRepository;

    @Mock
    private PlayerStateRepository playerStateRepository;

    @Mock
    private GameEventMapper gameEventMapper;

    @InjectMocks
    private GameEventService gameEventService;

    @Test
    void shouldRecordEventSuccessfully() {
        PlayerProfile profile = buildProfile();
        RecordEventRequest request = new RecordEventRequest("PVP_MATCH",
                Map.of("matchId", "match-01", "result", "WIN"));
        GameEvent event = buildEvent("evt-100");
        RecordEventResponse expectedResponse = new RecordEventResponse("evt-100", "2026-01-01T00:00:00Z");

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(gameEventMapper.toGameEvent(eq(PLAYER_ID), eq(request))).thenReturn(event);
        when(gameEventRepository.appendEvent(event))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gameEventMapper.toRecordEventResponse(event)).thenReturn(expectedResponse);

        RecordEventResponse response = gameEventService.recordEvent(PLAYER_ID, request);

        assertThat(response.eventId()).isEqualTo("evt-100");
        assertThat(response.recordedAt()).isEqualTo("2026-01-01T00:00:00Z");
        verify(gameEventRepository).appendEvent(event);
    }

    @Test
    void shouldThrowWhenPlayerNotFound() {
        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(null));

        RecordEventRequest request = new RecordEventRequest("PVP_MATCH", Map.of());

        assertThatThrownBy(() -> gameEventService.recordEvent(PLAYER_ID, request))
                .isInstanceOf(PlayerNotFoundException.class);

        verifyNoInteractions(gameEventRepository);
    }

    @Test
    void shouldGenerateUniqueEventId() {
        PlayerProfile profile = buildProfile();
        RecordEventRequest request = new RecordEventRequest("LEVEL_PROGRESS", Map.of("xpDelta", 500));

        GameEvent event1 = buildEvent("evt-aaa");
        GameEvent event2 = buildEvent("evt-bbb");

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(gameEventMapper.toGameEvent(eq(PLAYER_ID), eq(request)))
                .thenReturn(event1)
                .thenReturn(event2);
        when(gameEventRepository.appendEvent(any(GameEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(gameEventMapper.toRecordEventResponse(any(GameEvent.class)))
                .thenReturn(new RecordEventResponse("evt-aaa", "2026-01-01T00:00:00Z"))
                .thenReturn(new RecordEventResponse("evt-bbb", "2026-01-01T00:00:01Z"));

        RecordEventResponse r1 = gameEventService.recordEvent(PLAYER_ID, request);
        RecordEventResponse r2 = gameEventService.recordEvent(PLAYER_ID, request);

        assertThat(r1.eventId()).isNotEqualTo(r2.eventId());

        ArgumentCaptor<GameEvent> captor = ArgumentCaptor.forClass(GameEvent.class);
        verify(gameEventRepository, times(2)).appendEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(GameEvent::getEventId)
                .containsExactly("evt-aaa", "evt-bbb");
    }

    @Test
    void shouldQueryNewestFirstWhenScanIndexForwardUnset() {
        PlayerProfile profile = buildProfile();
        GameEvent event = buildEvent("evt-200");
        Map<String, AttributeValue> lastKey = Map.of(
                "PK", AttributeValue.fromS(GameEvent.PK_PREFIX + PLAYER_ID),
                "SK", AttributeValue.fromS(GameEvent.SK_PREFIX + "2026-01-02T00:00:00Z#" + event.getEventId()));

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(gameEventRepository.queryEventsByPlayer(eq(PLAYER_ID), eq(22), eq(false), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new GameEventPage(List.of(event), lastKey)));
        when(gameEventMapper.toEventDto(event))
                .thenReturn(new GameEventDto("evt-200", "PVP_MATCH", "2026-01-01T00:00:00Z", Map.of()));

        EventsPageResponse response = gameEventService.getEvents(PLAYER_ID, 22, null, null);

        assertThat(response.events()).hasSize(1);
        assertThat(response.nextToken()).isNotNull();
        verify(gameEventRepository).queryEventsByPlayer(PLAYER_ID, 22, false, null);
    }

    @Test
    void shouldQueryOldestFirstWhenScanIndexForwardTrue() {
        PlayerProfile profile = buildProfile();

        when(playerStateRepository.getPlayer(PLAYER_ID))
                .thenReturn(CompletableFuture.completedFuture(profile));
        when(gameEventRepository.queryEventsByPlayer(eq(PLAYER_ID), eq(20), eq(true), isNull()))
                .thenReturn(CompletableFuture.completedFuture(new GameEventPage(List.of(), null)));

        EventsPageResponse response = gameEventService.getEvents(PLAYER_ID, 20, Boolean.TRUE, null);

        assertThat(response.events()).isEmpty();
        assertThat(response.nextToken()).isNull();
        verify(gameEventRepository).queryEventsByPlayer(PLAYER_ID, 20, true, null);
    }

    /**
     * Builds a minimal {@link PlayerProfile} fixture for the test player.
     *
     * @return populated profile
     */
    private static PlayerProfile buildProfile() {
        PlayerProfile p = new PlayerProfile();
        p.setPartitionKey(PlayerProfile.PK_PREFIX + PLAYER_ID);
        p.setSortKey(PlayerProfile.SK_PROFILE);
        p.setPlayerId(PLAYER_ID);
        p.setPlayerName("EventPlayer");
        p.setPlatform("PC");
        p.setTotalExperience(500L);
        p.setCurrentLevel(2);
        p.setVersion(1);
        p.setLastUpdatedAt("2026-01-01T00:00:00Z");
        return p;
    }

    /**
     * Builds a {@link GameEvent} fixture with the given event id.
     *
     * @param eventId event identifier
     * @return populated game event
     */
    private static GameEvent buildEvent(String eventId) {
        GameEvent e = new GameEvent();
        e.setPartitionKey(GameEvent.PK_PREFIX + PLAYER_ID);
        e.setSortKey(GameEvent.SK_PREFIX + "2026-01-01T00:00:00Z#" + eventId);
        e.setEntityType(GameEvent.ENTITY_TYPE);
        e.setEventId(eventId);
        e.setPlayerId(PLAYER_ID);
        e.setEventType("PVP_MATCH");
        e.setRecordedAt("2026-01-01T00:00:00Z");
        e.setTtl(1735689600L);
        return e;
    }
}
