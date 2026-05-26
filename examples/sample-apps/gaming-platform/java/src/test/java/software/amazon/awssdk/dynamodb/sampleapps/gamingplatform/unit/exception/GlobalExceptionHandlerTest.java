package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InsufficientFundsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerAlreadyExistsException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.PlayerNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.StaleVersionException;

/**
 * Unit tests for {@link GlobalExceptionHandler} HTTP mappings.
 *
 * <p>Uses a standalone {@link MockMvc} setup with a throw-on-demand controller to verify status
 * codes, error payloads, and timestamp fields for each mapped exception.
 */
@Tag("unit")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Builds a {@link MockMvc} instance wired with {@link GlobalExceptionHandler}
     * and a real bean validator.
     */
    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ExceptionThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void handleException_whenPlayerNotFound_shouldReturn404() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/player-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PLAYER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Player not found: missing"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenPlayerAlreadyExists_shouldReturn409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/player-exists").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PLAYER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Player already exists with id: dup"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenInsufficientFunds_shouldReturn409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/insufficient-funds").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Insufficient funds for player p1: required=10, available=5"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenStaleVersion_shouldReturn409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/stale-version").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"))
                .andExpect(jsonPath("$.message").value("Stale version for player p2: expectedVersion=2"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenValidationError_shouldReturn400() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("value: must not be blank"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenInvalidPaginationToken_shouldReturn400() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/bad-pagination-token").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid pagination token: bad-token"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenIllegalArgument_shouldReturn400() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/bad-arg").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("bad argument"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleException_whenUnexpectedException_shouldReturn500() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    /**
     * Asserts the response {@code timestamp} field is within 60 seconds of the current clock.
     *
     * @param result MVC result whose response body contains a JSON {@code timestamp} field
     * @throws Exception if JSON parsing or time parsing fails
     */
    private void assertPlausibleTimestamp(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        Instant ts = Instant.parse(root.get("timestamp").asText());
        Instant now = Instant.now();
        assertThat(ts)
                .isAfter(now.minusSeconds(60))
                .isBefore(now.plusSeconds(60));
    }

    /**
     * Minimal REST controller that throws every mapped exception on demand.
     */
    @RestController
    static class ExceptionThrowingController {

        /**
         * Request body with a single mandatory string field used to trigger validation errors.
         *
         * @param value must not be blank
         */
        record ValidatedBody(@NotBlank String value) {}

        /**
         * Throws {@link PlayerNotFoundException} to exercise the 404 handler mapping.
         */
        @GetMapping("/test/player-not-found")
        void playerNotFound() {
            throw new PlayerNotFoundException("missing");
        }

        /**
         * Throws {@link PlayerAlreadyExistsException} to exercise the 409 handler mapping.
         */
        @GetMapping("/test/player-exists")
        void playerExists() {
            throw new PlayerAlreadyExistsException("dup");
        }

        /**
         * Throws {@link InsufficientFundsException} to exercise the 409 handler mapping.
         */
        @GetMapping("/test/insufficient-funds")
        void insufficientFunds() {
            throw new InsufficientFundsException("p1", 10, 5);
        }

        /**
         * Throws {@link StaleVersionException} to exercise the 409 handler mapping.
         */
        @GetMapping("/test/stale-version")
        void staleVersion() {
            throw new StaleVersionException("p2", 2L);
        }

        /**
         * Accepts a validated body to trigger a 400 on constraint violations.
         *
         * @param body request body subject to {@code @NotBlank} validation
         */
        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody ValidatedBody body) {
            // unreachable when validation fails
        }

        /**
         * Throws {@link InvalidPaginationTokenException} to exercise the 400 handler mapping.
         */
        @GetMapping("/test/bad-pagination-token")
        void badPaginationToken() {
            throw new InvalidPaginationTokenException("bad-token");
        }

        /**
         * Throws {@link IllegalArgumentException} to exercise the 400 handler mapping.
         */
        @GetMapping("/test/bad-arg")
        void badArg() {
            throw new IllegalArgumentException("bad argument");
        }

        /**
         * Throws {@link IllegalStateException} to exercise the 500 handler mapping.
         */
        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom");
        }
    }
}
