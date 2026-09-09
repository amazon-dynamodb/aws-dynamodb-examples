package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.dto;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.dto.EventsPageResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EventsPageResponse} JSON serialization.
 *
 * <p>Serializes DTOs with Jackson and inspects the JSON output.
 */
@Tag("unit")
class EventsPageResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialize_whenNextTokenNullEmptyOrBlank_shouldOmitNextTokenProperty() throws Exception {
        for (String nextToken : new String[] {null, "", "   "}) {
            String json = objectMapper.writeValueAsString(new EventsPageResponse(List.of(), nextToken));
            JsonNode root = objectMapper.readTree(json);

            assertThat(root.has("nextToken")).isFalse();
            assertThat(root.get("events").isArray()).isTrue();
        }
    }

    @Test
    void serialize_whenNextTokenPresent_shouldIncludeNextTokenProperty() throws Exception {
        String json = objectMapper.writeValueAsString(new EventsPageResponse(List.of(), "page-2"));
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("nextToken").asText()).isEqualTo("page-2");
    }
}
