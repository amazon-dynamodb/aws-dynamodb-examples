package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.PurchaseRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventRequest;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.RecordEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.mapper.GameEventMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.CurrencyEarnReason;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEvent;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.model.GameEventType;

/**
 * Unit tests for {@link GameEventMapper}.
 *
 * <p>Verifies mapping from record, purchase, and currency-grant requests into {@link GameEvent}
 * items including TTL, keys, and response DTO conversion.
 */
@Tag("unit")
class GameEventMapperTest {

    @Test
    void toGameEvent_mapsPayloadAndSetsTtlWhenPositiveTtl() {
        GameEventMapper mapper = new GameEventMapper(3600);
        RecordEventRequest request = new RecordEventRequest(
                GameEventType.PVP_MATCH.name(),
                Map.of(
                        "matchId", "m1",
                        "opponentPlayerId", "opp",
                        "result", "WIN",
                        "playerScore", 3,
                        "opponentScore", 1));

        GameEvent event = mapper.toGameEvent("player-a", request);

        assertThat(event.getPlayerId()).isEqualTo("player-a");
        assertThat(event.getPartitionKey()).isEqualTo(GameEvent.PK_PREFIX + "player-a");
        assertThat(event.getSortKey()).startsWith(GameEvent.SK_PREFIX);
        assertThat(event.getEventType()).isEqualTo(GameEventType.PVP_MATCH.name());
        assertThat(event.getMatchId()).isEqualTo("m1");
        assertThat(event.getOpponentPlayerId()).isEqualTo("opp");
        assertThat(event.getMatchResult()).isEqualTo("WIN");
        assertThat(event.getPlayerScore()).isEqualTo(3);
        assertThat(event.getOpponentScore()).isEqualTo(1);
        assertThat(event.getTtl()).isNotNull();
        assertThat(event.getTtl()).isGreaterThan(0L);
    }

    @Test
    void toGameEvent_withZeroTtl_omitsTtlAttribute() {
        GameEventMapper mapper = new GameEventMapper(0);
        RecordEventRequest request = new RecordEventRequest(GameEventType.LEVEL_PROGRESS.name(), Map.of());
        GameEvent event = mapper.toGameEvent("p", request);
        assertThat(event.getTtl()).isNull();
    }

    @Test
    void toPurchaseEvent_usesDeterministicEventIdFromClientRequest() {
        GameEventMapper mapper = new GameEventMapper(60);
        PurchaseRequest purchase = new PurchaseRequest("item-1", 100, "idem-key-xyz");

        GameEvent e1 = mapper.toPurchaseEvent("player-b", purchase);
        GameEvent e2 = mapper.toPurchaseEvent("player-b", purchase);

        assertThat(e1.getEventId()).isEqualTo(e2.getEventId());
        assertThat(e1.getSortKey()).isEqualTo(e2.getSortKey());
        assertThat(e1.getSortKey()).isEqualTo(GameEvent.SK_PREFIX + "PURCHASE#" + e1.getEventId());
        assertThat(e1.getEventType()).isEqualTo(GameEventType.PURCHASE.name());
        assertThat(e1.getItemId()).isEqualTo("item-1");
        assertThat(e1.getCurrencyDelta()).isEqualTo(-100);
    }

    @Test
    void toRecordEventResponse_mapsIds() {
        GameEventMapper mapper = new GameEventMapper(1);
        GameEvent event = new GameEvent();
        event.setEventId("eid");
        event.setRecordedAt("ts");
        RecordEventResponse response = mapper.toRecordEventResponse(event);
        assertThat(response.eventId()).isEqualTo("eid");
        assertThat(response.recordedAt()).isEqualTo("ts");
    }

    @Test
    void toEventDto_rebuildsPayloadFromModel() {
        GameEventMapper mapper = new GameEventMapper(1);
        GameEvent event = new GameEvent();
        event.setEventId("e1");
        event.setEventType("X");
        event.setRecordedAt("t");
        event.setMatchId("mid");
        event.setXpDelta(5L);

        var dto = mapper.toEventDto(event);
        assertThat(dto.eventId()).isEqualTo("e1");
        assertThat(dto.eventAttributes()).containsEntry("matchId", "mid");
        assertThat(dto.eventAttributes()).containsEntry("xpDelta", 5L);
    }

    @Test
    void toCurrencyGrantEvent_usesDeterministicEventIdFromClientRequestId() {
        GameEventMapper mapper = new GameEventMapper(60);

        GameEvent e1 = mapper.toCurrencyGrantEvent("player-c", 500L, CurrencyEarnReason.MATCH_WIN, "req-abc");
        GameEvent e2 = mapper.toCurrencyGrantEvent("player-c", 500L, CurrencyEarnReason.MATCH_WIN, "req-abc");

        assertThat(e1.getEventId()).isEqualTo(e2.getEventId());
        assertThat(e1.getSortKey()).isEqualTo(e2.getSortKey());
        assertThat(e1.getSortKey()).isEqualTo(GameEvent.SK_PREFIX + "CURRENCY_GRANT#" + e1.getEventId());
        assertThat(e1.getEventType()).isEqualTo(GameEventType.CURRENCY_GRANT.name());
        assertThat(e1.getCurrencyDelta()).isEqualTo(500L);
        assertThat(e1.getReason()).isEqualTo(CurrencyEarnReason.MATCH_WIN.name());
        assertThat(e1.getPlayerId()).isEqualTo("player-c");
        assertThat(e1.getPartitionKey()).isEqualTo(GameEvent.PK_PREFIX + "player-c");
        assertThat(e1.getTtl()).isNotNull().isGreaterThan(0L);
    }

    @Test
    void toCurrencyGrantEvent_differentClientRequestIds_produceDifferentEventIds() {
        GameEventMapper mapper = new GameEventMapper(60);

        GameEvent e1 = mapper.toCurrencyGrantEvent("player-c", 100L, CurrencyEarnReason.DAILY_LOGIN, "req-1");
        GameEvent e2 = mapper.toCurrencyGrantEvent("player-c", 100L, CurrencyEarnReason.DAILY_LOGIN, "req-2");

        assertThat(e1.getEventId()).isNotEqualTo(e2.getEventId());
        assertThat(e1.getSortKey()).isNotEqualTo(e2.getSortKey());
    }
}
