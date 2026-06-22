package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.WalletNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.InternalServerErrorException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Unit tests for {@link GlobalExceptionHandler} HTTP mappings.
 *
 * <p>Uses a standalone {@link MockMvc} setup with a throw-on-demand controller to verify status
 * codes, error payloads, headers, and timestamp fields for each mapped exception. End-to-end
 * path-variable validation lives in the per-controller {@code @WebMvcTest} slices, where the full
 * MVC method-validation infrastructure is active.
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
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ExceptionThrowingController())
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
    void handleException_whenWalletNotFound_shouldReturn404() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/wallet-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WALLET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Wallet not found for player: w1"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handlePlayerNotFound_whenPlayerMissing_shouldLogAtDebug() throws Exception {
        ListAppender<ILoggingEvent> appender = attachDebugAppender(GlobalExceptionHandler.class);

        mockMvc.perform(get("/test/player-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        assertThat(levelsFor(appender, "PLAYER_NOT_FOUND")).containsExactly(Level.DEBUG);
    }

    @Test
    void handleWalletNotFound_whenWalletMissing_shouldLogAtDebug() throws Exception {
        ListAppender<ILoggingEvent> appender = attachDebugAppender(GlobalExceptionHandler.class);

        mockMvc.perform(get("/test/wallet-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        assertThat(levelsFor(appender, "WALLET_NOT_FOUND")).containsExactly(Level.DEBUG);
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

    @Test
    void handleDynamoDbException_whenThroughputExceeded_shouldReturn503WithRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ddb-throughput").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("THROUGHPUT_EXCEEDED"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleDynamoDbException_whenRequestLimitExceeded_shouldReturn503WithRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ddb-request-limit").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("REQUEST_LIMIT_EXCEEDED"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleDynamoDbException_whenResourceNotFound_shouldReturn503WithoutRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ddb-table-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error").value("TABLE_NOT_FOUND"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleDynamoDbException_whenInternalServerError_shouldReturn503WithRetryAfter() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ddb-internal").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("DYNAMODB_INTERNAL_ERROR"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleGeneric_whenDynamoDbExceptionWrappedInCompletionException_shouldMapToDynamoDbCode() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/ddb-wrapped").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("THROUGHPUT_EXCEEDED"))
                .andReturn();
        assertPlausibleTimestamp(result);
    }

    @Test
    void handleConstraintViolation_whenPathVariableViolation_shouldReturn400() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/constraint-violation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("playerId")))
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
     * Attaches a fresh {@link ListAppender} to the logger of {@code type} and forces DEBUG level.
     *
     * <p>This lets a test observe DEBUG events that the default level would otherwise filter out.
     *
     * @param type class whose logger is captured
     * @return started appender that collects emitted events
     */
    private static ListAppender<ILoggingEvent> attachDebugAppender(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /**
     * Collects the levels of captured events whose formatted message contains {@code messagePart}.
     *
     * @param appender appender holding the captured events
     * @param messagePart text the event message must contain
     * @return matching event levels in capture order
     */
    private static List<Level> levelsFor(ListAppender<ILoggingEvent> appender, String messagePart) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(messagePart))
                .map(ILoggingEvent::getLevel)
                .toList();
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
         * Throws {@link WalletNotFoundException} to exercise the 404 handler mapping.
         */
        @GetMapping("/test/wallet-not-found")
        void walletNotFound() {
            throw new WalletNotFoundException("w1");
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

        /**
         * Throws {@link ProvisionedThroughputExceededException} to exercise the 503 throttling mapping.
         */
        @GetMapping("/test/ddb-throughput")
        void ddbThroughput() {
            throw ProvisionedThroughputExceededException.builder().message("throttled").build();
        }

        /**
         * Throws {@link RequestLimitExceededException} to exercise the 503 request-limit mapping.
         */
        @GetMapping("/test/ddb-request-limit")
        void ddbRequestLimit() {
            throw RequestLimitExceededException.builder().message("request limit").build();
        }

        /**
         * Throws {@link ResourceNotFoundException} to exercise the 503 table-not-found mapping.
         */
        @GetMapping("/test/ddb-table-not-found")
        void ddbTableNotFound() {
            throw ResourceNotFoundException.builder().message("table missing").build();
        }

        /**
         * Throws {@link InternalServerErrorException} to exercise the 503 transient-fault mapping.
         */
        @GetMapping("/test/ddb-internal")
        void ddbInternal() {
            throw InternalServerErrorException.builder().message("ddb internal").build();
        }

        /**
         * Throws a {@link CompletionException} wrapping a DynamoDB fault, mimicking how
         * {@code CompletableFuture.join()} surfaces dependency errors from the service layer.
         */
        @GetMapping("/test/ddb-wrapped")
        void ddbWrapped() {
            throw new CompletionException(
                    ProvisionedThroughputExceededException.builder().message("throttled").build());
        }

        /**
         * Throws a {@link ConstraintViolationException} built from a real validator so the handler
         * can be checked for the 400 mapping and leaf property naming.
         */
        @GetMapping("/test/constraint-violation")
        void constraintViolation() {
            throw buildPlayerIdViolation();
        }

        /**
         * Builds a {@link ConstraintViolationException} by validating an out-of-pattern player id,
         * yielding a violation whose leaf property path is {@code playerId}.
         *
         * @return constraint violation exception carrying the offending property path
         */
        private ConstraintViolationException buildPlayerIdViolation() {
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                Set<ConstraintViolation<PlayerIdHolder>> violations =
                        validator.validate(new PlayerIdHolder("bad#id"));
                return new ConstraintViolationException(violations);
            }
        }

        /**
         * Holder used only to produce a real {@code playerId} constraint violation.
         *
         * @param playerId value validated against the allowed character set
         */
        record PlayerIdHolder(@Pattern(regexp = "^[A-Za-z0-9_-]+$") String playerId) {}
    }
}
