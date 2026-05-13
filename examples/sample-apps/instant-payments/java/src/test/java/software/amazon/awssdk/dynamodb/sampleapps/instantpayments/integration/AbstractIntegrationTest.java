package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.DynamoDbTableTestReset;

/**
 * Base class for integration and smoke tests that require a DynamoDB Local instance.
 *
 * <p>Uses Testcontainers to spin up a DynamoDB Local Docker container once for
 * the entire test suite. The container is started in a static initializer and
 * stays alive for the JVM lifetime, ensuring all test classes share the same
 * container and the same Spring context (port never changes).
 *
 * <p>If Docker is not available (e.g. no daemon running, Rancher Desktop socket
 * not configured), tests are <em>skipped</em> gracefully via JUnit assumptions
 * rather than failing the build.
 *
 * <p>Subclasses inherit the full Spring context and can inject beans normally.
 * Smoke-style API tests can use the provided {@link MockMvc} field directly.
 *
 * <p>Example usage:
 * <pre>{@code
 * class PaymentIntegrationTest extends AbstractIntegrationTest {
 *
 *     @Autowired
 *     private PaymentService paymentService;
 *
 *     @Test
 *     void shouldCreatePayment() {
 *         // test code using real DynamoDB Local
 *     }
 * }
 * }</pre>
 *
 * <p>For API-level smoke tests, {@link MockMvc} is provided by {@link AutoConfigureMockMvc} on
 * this base class, so subclasses can perform HTTP requests directly.
 *
 * <p>Profile {@code test} enables {@link DynamoDbTableTestReset}: before each test method the
 * configured table is dropped, recreated with DynamoDB Streams, and re-seeded from
 * {@link software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.SeedAccountsData} (same
 * rows as application startup) so tests do not share mutated state.
 *
 * <p>{@link AutoConfigureMockMvc} is declared here next to {@link SpringBootTest} so the test
 * context registers {@link org.springframework.test.web.servlet.MockMvc}; keep it co-located for
 * IDE tooling and framework ordering.
 *
 * <p>The DynamoDB Streams poller is enabled by default. Tests that need to keep payments in a
 * pre-processing state can opt out locally with
 * {@code @TestPropertySource(properties = "dynamodb.streams.enabled=false")}.
 *
 * <p>{@link DirtiesContext} is declared here with {@link DirtiesContext.ClassMode#AFTER_CLASS} so
 * each smoke/integration test class gets a fresh Spring test context while still sharing the
 * JVM-scoped DynamoDB Local container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DynamoDbTableTestReset.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AbstractIntegrationTest.class);
    private static final int DYNAMODB_PORT = 8000;
    private static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:latest";

    /** JVM-scoped DynamoDB Local; {@link #registerDynamoDbLocalShutdownHook(GenericContainer)} calls {@link GenericContainer#close()}. */
    private static final GenericContainer<?> dynamoDbLocalContainer = createDynamoDbLocalContainer();

    /**
     * Creates and starts the shared container. Not try-with-resources: the instance must outlive all tests;
     * {@link #registerDynamoDbLocalShutdownHook(GenericContainer)} closes it on JVM exit.
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> createDynamoDbLocalContainer() {
        GenericContainer<?> container = new GenericContainer<>(DYNAMODB_LOCAL_IMAGE)
                .withExposedPorts(DYNAMODB_PORT)
                .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
                .waitingFor(Wait.forListeningPort());
        try {
            container.start();
            registerDynamoDbLocalShutdownHook(container);
        } catch (Exception e) {
            log.warn("Could not start DynamoDB Local container — Docker may not be available. "
                    + "Integration and smoke tests will be skipped. Error: {}", e.getMessage());
            try {
                container.close();
            } catch (RuntimeException closeEx) {
                log.debug("DynamoDB Local container cleanup after failed start: {}", closeEx.getMessage());
            }
        }
        return container;
    }

    private static void registerDynamoDbLocalShutdownHook(GenericContainer<?> container) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (container.isRunning()) {
                    container.close();
                }
            } catch (RuntimeException ex) {
                log.debug("DynamoDB Local shutdown hook: {}", ex.getMessage());
            }
        }, "dynamodb-local-test-shutdown"));
    }

    @Autowired
    private DynamoDbTableTestReset dynamoDbTableTestReset;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    /** MockMvc instance for performing HTTP requests in smoke/integration tests. */
    @Autowired
    protected MockMvc mockMvc;

    @BeforeAll
    static void ensureDockerAvailable() {
        assumeTrue(dynamoDbLocalContainer.isRunning(),
                "DynamoDB Local container is not running — Docker may not be available. "
                        + "Run with -P rancher-desktop. "
                        + "For Rancher Desktop, ensure DOCKER_HOST=unix://$HOME/.rd/docker.sock.");
    }

    /**
     * Isolates each test from shared DynamoDB state (including across test classes on the same container).
     */
    @BeforeEach
    void resetDynamoDbTable() {
        dynamoDbTableTestReset.deleteRecreateAndSeed();
    }

    /**
     * Configures the Spring context to point to the Testcontainers DynamoDB Local instance.
     *
     * <p>Uses lambdas for lazy evaluation so the mapped port is only resolved when
     * Spring actually reads the property (after the container is confirmed running).
     * Falls back to a dummy endpoint if the container did not start, allowing the
     * context to load and tests to be skipped in {@link #ensureDockerAvailable()}.
     */
    @DynamicPropertySource
    static void dynamoDbProperties(DynamicPropertyRegistry registry) {
        registry.add("dynamodb.endpoint",
                () -> dynamoDbLocalContainer.isRunning()
                        ? "http://localhost:" + dynamoDbLocalContainer.getMappedPort(DYNAMODB_PORT)
                        : "http://localhost:0");
        registry.add("dynamodb.region", () -> "eu-west-1");
        registry.add("dynamodb.client-type", () -> "high-level");
    }

    /**
     * Inserts a reservation row directly into DynamoDB Local so end-to-end tests can exercise mixed
     * reservation states that are not all reachable through the public payment flow alone.
     *
     * @param accountId business account id that owns the reservation partition
     * @param reservationId business reservation id stored after {@code RESERVATION#}
     * @param paymentId payment id associated with the reservation
     * @param amount numeric amount stored on the reservation item
     * @param status reservation status such as {@code ACTIVE}, {@code CONSUMED}, or {@code RELEASED}
     */
    protected void seedReservation(String accountId,
                                   String reservationId,
                                   String paymentId,
                                   String amount,
                                   String status) {
        String accountKey = "ACCOUNT#" + accountId;
        Map<String, AttributeValue> item = Map.of(
                "PK", AttributeValue.builder().s(accountKey).build(),
                "SK", AttributeValue.builder().s("RESERVATION#" + reservationId).build(),
                "entityType", AttributeValue.builder().s("RESERVATION").build(),
                "reservationId", AttributeValue.builder().s(reservationId).build(),
                "paymentId", AttributeValue.builder().s(paymentId).build(),
                "amount", AttributeValue.builder().n(amount).build(),
                "status", AttributeValue.builder().s(status).build(),
                "createdAtUtc", AttributeValue.builder().s(Instant.now().toString()).build());

        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();
        dynamoDbAsyncClient.putItem(request).join();
    }

    /**
     * Asserts one reservation entry inside the JSON response returned by an account query endpoint.
     *
     * @param actions result actions returned by {@code MockMvc.perform(...)}
     * @param index zero-based index inside {@code $.reservations}
     * @param reservationId expected reservation id
     * @param paymentId expected payment id
     * @param amount expected numeric amount
     * @param status expected reservation status string
     * @throws Exception if one of the JSON-path assertions fails
     */
    protected void assertReservation(ResultActions actions,
                                     int index,
                                     String reservationId,
                                     String paymentId,
                                     Number amount,
                                     String status) throws Exception {
        actions
                .andExpect(jsonPath("$.reservations[" + index + "].reservationId").value(reservationId))
                .andExpect(jsonPath("$.reservations[" + index + "].paymentId").value(paymentId))
                .andExpect(jsonPath("$.reservations[" + index + "].amount").value(amount))
                .andExpect(jsonPath("$.reservations[" + index + "].status").value(status));
    }

    /**
     * Asserts a whole reservation array in order using parallel lists for the expected fields.
     *
     * @param actions result actions returned by {@code MockMvc.perform(...)}
     * @param reservationIds expected reservation ids in array order
     * @param paymentIds expected payment ids in array order
     * @param amounts expected numeric amounts in array order
     * @param statuses expected reservation status values in array order
     * @throws Exception if any reservation assertion fails
     */
    protected void assertReservations(ResultActions actions,
                                      List<String> reservationIds,
                                      List<String> paymentIds,
                                      List<? extends Number> amounts,
                                      List<String> statuses) throws Exception {
        if (reservationIds.size() != paymentIds.size()
                || reservationIds.size() != amounts.size()
                || reservationIds.size() != statuses.size()) {
            throw new IllegalArgumentException("Reservation assertion lists must have the same size");
        }
        for (int i = 0; i < reservationIds.size(); i++) {
            assertReservation(actions, i, reservationIds.get(i), paymentIds.get(i), amounts.get(i), statuses.get(i));
        }
    }

    /**
     * Creates an outbound payment through the public API and returns its generated payment id.
     *
     * @param debtorAccountId business debtor account id used in the request body
     * @param amount numeric payment amount serialized into the request body
     * @param creditorName creditor name used to distinguish test scenarios in logs
     * @return created payment id from the HTTP response body
     * @throws Exception if the HTTP request fails or the response cannot be parsed
     */
    protected String createPayment(String debtorAccountId,
                                   String amount,
                                   String creditorName) throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "%s",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "%s",
                  "amount": %s,
                  "currency": "USD"
                }""".formatted(idempotencyKey, debtorAccountId, creditorName, amount);

        MvcResult result = mockMvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();

        return JsonPathSupport.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

    /**
     * Triggers synchronous processing for an outbound payment through the public API.
     *
     * @param paymentId payment id to process
     * @throws Exception if the HTTP request fails
     */
    protected void processPayment(String paymentId) throws Exception {
        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    /**
     * Derives the deterministic reservation id created by the outbound payment flow.
     *
     * @param paymentId payment id returned by the create-payment API
     * @return reservation id in the form {@code res_<paymentId>}
     */
    protected String reservationIdForPayment(String paymentId) {
        return "res_" + paymentId;
    }

    /**
     * Builds the JSON body for {@code POST /batch-get-reservations} from the given identifiers.
     *
     * @param reservationIds reservation ids to serialize in request order
     * @return compact JSON body containing {@code reservationIds}
     */
    protected String batchGetReservationsRequestBody(List<String> reservationIds) {
        String quotedIds = String.join(",", reservationIds.stream().map(id -> "\"" + id + "\"").toList());
        return "{\"reservationIds\":[" + quotedIds + "]}";
    }

}
