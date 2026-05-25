package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.integration.utils.DynamoDbTableTestReset;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration and smoke tests that need a DynamoDB Local instance.
 *
 * <p>Uses Testcontainers to run DynamoDB Local once per JVM. If Docker is unavailable, tests skip
 * via JUnit assumptions instead of failing the build.
 *
 * <p>Before each test, all three tables (PlayerState, GameEvents, LeaderboardAggregate) are
 * deleted, recreated, and seeded via {@link DynamoDbTableTestReset}.
 *
 * <p>Subclasses inherit {@link #mockMvc} and HTTP helper methods such as {@link #registerPlayer}
 * for repeatable player setup during integration scenarios.
 *
 * <p>{@code dynamodb.client-type} defaults to {@code high-level} from {@code application-test.yml}.
 * Low-level variants override it in their own {@link DynamicPropertySource} method. This base class
 * must not register that property because superclass dynamic sources run after subclass sources.
 *
 * <p>Uses {@link SpringBootTest.WebEnvironment#MOCK} because tests exercise the API through
 * {@link MockMvc} only. No embedded servlet container is required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DynamoDbTableTestReset.class)
public abstract class AbstractIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractIntegrationTest.class);
    private static final int DYNAMODB_PORT = 8000;
    private static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:latest";

    private static final GenericContainer<?> dynamoDbLocalContainer = createDynamoDbLocalContainer();

    @Autowired
    protected MockMvc mockMvc;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private DynamoDbTableTestReset dynamoDbTableTestReset;

    /**
     * Starts DynamoDB Local once, or leaves the handle in a stopped state when Docker is unavailable.
     *
     * @return container reference (not necessarily running)
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> createDynamoDbLocalContainer() {
        GenericContainer<?> container = new GenericContainer<>(DYNAMODB_LOCAL_IMAGE)
                .withExposedPorts(DYNAMODB_PORT)
                .withCommand("-jar DynamoDBLocal.jar -sharedDb -inMemory")
                .waitingFor(Wait.forListeningPort());
        try {
            container.start();
            registerDynamoDbLocalShutdownHook();
        } catch (Exception e) {
            logger.warn("Could not start DynamoDB Local container: Docker may not be available. "
                    + "Integration and smoke tests will be skipped. Error: {}", e.getMessage());
            try {
                container.close();
            } catch (RuntimeException closeEx) {
                logger.debug("DynamoDB Local container cleanup after failed start: {}", closeEx.getMessage());
            }
        }
        return container;
    }

    /**
     * Registers JVM shutdown cleanup so the shared container stops after Spring contexts close.
     */
    private static void registerDynamoDbLocalShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (dynamoDbLocalContainer.isRunning()) {
                    dynamoDbLocalContainer.close();
                }
            } catch (RuntimeException ex) {
                logger.debug("DynamoDB Local shutdown hook: {}", ex.getMessage());
            }
        }, "gaming-platform-dynamodb-local-test-shutdown"));
    }

    /**
     * Skips tests when the shared container did not start.
     */
    @BeforeAll
    static void ensureDockerAvailable() {
        assumeTrue(dynamoDbLocalContainer.isRunning(),
                "DynamoDB Local container is not running: Docker may not be available. "
                        + "Run with -P rancher-desktop. "
                        + "For Rancher Desktop, ensure DOCKER_HOST=unix://$HOME/.rd/docker.sock.");
    }

    /**
     * Resets all tables and seeds data before each test for full isolation.
     */
    @BeforeEach
    void resetDynamoDbTables() {
        dynamoDbTableTestReset.deleteRecreateAndSeed();
    }

    /**
     * Points Spring at the Testcontainers DynamoDB Local instance.
     *
     * @param registry Spring dynamic property registry
     */
    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint",
                () -> dynamoDbLocalContainer.isRunning()
                        ? "http://localhost:" + dynamoDbLocalContainer.getMappedPort(DYNAMODB_PORT)
                        : "http://localhost:0");
        registry.add("dynamodb.region", () -> "eu-west-1");
    }

    /**
     * Registers a player via {@code POST /api/v1/players} and returns the generated player id.
     *
     * <p>Example: {@code registerPlayer("LobbyHero", "PC", "steam-lobby-001")} creates a new player
     * and returns the id from the {@code 201 Created} response body.
     *
     * @param playerName     display name for the new player
     * @param platform       gaming platform (PC, IOS, ANDROID)
     * @param platformUserId external platform identity
     * @return the generated player id from the response
     * @throws Exception if the HTTP request fails
     */
    protected String registerPlayer(String playerName, String platform, String platformUserId) throws Exception {
        String body = """
                {
                  "platform": "%s",
                  "platformUserId": "%s",
                  "playerName": "%s"
                }
                """.formatted(platform, platformUserId, playerName);

        String responseJson = mockMvc.perform(post("/api/v1/players")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseJson).get("playerId").asText();
    }

    /**
     * Posts a {@code PVP_MATCH} game event for the given player using the REST API.
     *
     * <p>Example: {@code recordPvp(playerId, "match-42")} stores a win event with {@code playerScore}
     * set so the streams listener can project a leaderboard entry.
     *
     * @param playerId player who recorded the match
     * @param matchId  unique match identifier embedded in the event attributes
     * @throws Exception if the HTTP request fails
     */
    protected void recordPvp(String playerId, String matchId) throws Exception {
        mockMvc.perform(post("/api/v1/players/{playerId}/events", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "PVP_MATCH",
                                  "eventAttributes": {
                                    "matchId": "%s",
                                    "opponentPlayerId": "opp",
                                    "result": "WIN",
                                    "playerScore": 10,
                                    "opponentScore": 1
                                  }
                                }
                                """.formatted(matchId)))
                .andExpect(status().isCreated());
    }
}
