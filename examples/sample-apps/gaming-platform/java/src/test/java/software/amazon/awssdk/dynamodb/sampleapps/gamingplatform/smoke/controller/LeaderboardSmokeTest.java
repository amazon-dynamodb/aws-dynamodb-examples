package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.controller.LeaderboardController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.smoke.AbstractSmokeTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests for {@link LeaderboardController}.
 *
 * <p>Extends {@link AbstractSmokeTest} and queries scope {@code SEASON#default#MODE#ranked}
 * through MockMvc. Asserts HTTP 200 and a JSON entries array without requiring pre-populated rows.
 */
@Tag("smoke")
class LeaderboardSmokeTest extends AbstractSmokeTest {

    @Test
    void shouldQueryLeaderboard() throws Exception {
        mockMvc.perform(get("/api/v1/leaderboards/{scope}", "SEASON#default#MODE#ranked")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SEASON#default#MODE#ranked"))
                .andExpect(jsonPath("$.entries").isArray());
    }
}
