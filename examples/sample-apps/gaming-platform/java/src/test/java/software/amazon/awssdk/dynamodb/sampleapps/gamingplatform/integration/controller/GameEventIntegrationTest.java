package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.GameEventController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GameEventController}.
 *
 * <p>Extends {@link AbstractIntegrationTest} with MockMvc and the high-level enhanced client against
 * DynamoDB Local. Exercises {@code POST /api/v1/players/{playerId}/events} and paginated
 * {@code GET /api/v1/players/{playerId}/events} for supported event types, pagination, and error paths.
 */
@Tag("integration")
class GameEventIntegrationTest extends AbstractIntegrationTest {

    @Test
    void recordEvent_whenPvpMatch_shouldPersistToDynamoDb() throws Exception {
        String playerId = registerPlayer("EventTestPlayer", "PC", "steam-evt-001");

        mockMvc.perform(post("/api/v1/players/{playerId}/events", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PVP_MATCH",
                                  "eventAttributes": {
                                    "matchId": "match-int-01",
                                    "opponentPlayerId": "opponent-01",
                                    "result": "WIN",
                                    "playerScore": 3,
                                    "opponentScore": 1
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.recordedAt").isNotEmpty());
    }

    @Test
    void recordEvent_whenPurchaseType_shouldPersistToDynamoDb() throws Exception {
        String playerId = registerPlayer("EconomyLedger", "PC", "steam-evt-eco");

        mockMvc.perform(post("/api/v1/players/{playerId}/events", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PURCHASE",
                                  "eventAttributes": {
                                    "itemId": "cosmetic-banner",
                                    "currencyDelta": -250,
                                    "reason": "CATALOG_PURCHASE_TEST"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").isNotEmpty());
    }

    @Test
    void recordEvent_whenLevelProgressType_shouldPersistToDynamoDb() throws Exception {
        String playerId = registerPlayer("LevelGrinder", "PC", "steam-evt-lvl");

        mockMvc.perform(post("/api/v1/players/{playerId}/events", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "LEVEL_PROGRESS",
                                  "eventAttributes": {
                                    "xpDelta": 50,
                                    "reason": "DAILY_BONUS_EVENT"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").isNotEmpty());
    }

    @Test
    void recordEvent_whenPlayerMissing_shouldReturn404() throws Exception {
        mockMvc.perform(post("/api/v1/players/nonexistent-player/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "LOGIN",
                                  "eventAttributes": {}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void listEvents_whenPlayerUnknown_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/v1/players/nonexistent-player/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"));
    }

    @Test
    void listEvents_whenSinglePage_shouldOmitNextToken() throws Exception {
        String playerId = registerPlayer("PaginationNoTokenPlayer", "PC", "steam-pag-001");

        mockMvc.perform(get("/api/v1/players/{playerId}/events", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    void listEvents_whenMultiplePagesExist_shouldPaginate() throws Exception {
        String playerId = registerPlayer("PaginationMultiPage", "PC", "steam-pag-mp-002");

        for (int i = 0; i < 22; i++) {
            mockMvc.perform(post("/api/v1/players/{playerId}/events", playerId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "eventType": "PURCHASE",
                                      "eventAttributes": {
                                        "itemId": "pag-item-%d",
                                        "currencyDelta": %d
                                      }
                                    }
                                    """.formatted(i, -10)))
                    .andExpect(status().isCreated());
        }

        MvcResult first = mockMvc.perform(get("/api/v1/players/{playerId}/events", playerId)
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode pageOne = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(pageOne.path("events")).hasSize(20);
        assertThat(pageOne.path("nextToken").isMissingNode()).isFalse();

        mockMvc.perform(get("/api/v1/players/{playerId}/events", playerId)
                        .queryParam("limit", "20")
                        .queryParam("nextToken", pageOne.get("nextToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    void listEvents_whenDefaultSort_shouldReturnNewestFirst() throws Exception {
        String playerId = registerPlayer("NewestFirst", "PC", "steam-order-001");

        recordPvp(playerId, "match-a");
        Thread.sleep(5);
        recordPvp(playerId, "match-b");
        Thread.sleep(5);
        recordPvp(playerId, "match-c");

        JsonNode tree = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/players/{playerId}/events", playerId).queryParam("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(tree.path("events").path(0).path("eventAttributes").path("matchId").asText())
                .isEqualTo("match-c");
        assertThat(tree.path("events").path(2).path("eventAttributes").path("matchId").asText())
                .isEqualTo("match-a");
    }

    @Test
    void listEvents_whenScanIndexForwardTrue_shouldReturnOldestFirst() throws Exception {
        String playerId = registerPlayer("OldestFirst", "PC", "steam-order-002");

        recordPvp(playerId, "m-1");
        Thread.sleep(5);
        recordPvp(playerId, "m-2");
        Thread.sleep(5);
        recordPvp(playerId, "m-3");

        JsonNode tree = objectMapper.readTree(mockMvc.perform(
                        get("/api/v1/players/{playerId}/events", playerId)
                                .queryParam("limit", "10")
                                .queryParam("scanIndexForward", "true"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(tree.path("events").path(0).path("eventAttributes").path("matchId").asText())
                .isEqualTo("m-1");
        assertThat(tree.path("events").path(2).path("eventAttributes").path("matchId").asText())
                .isEqualTo("m-3");
    }
}
