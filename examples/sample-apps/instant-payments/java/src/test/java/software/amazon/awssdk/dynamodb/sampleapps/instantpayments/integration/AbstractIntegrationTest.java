package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.SeedAccountsData;
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
 *     void createPayment_whenValidRequest_shouldCreatePayment() {
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
 * {@link SeedAccountsData} (same
 * rows as application startup) so tests do not share mutated state.
 *
 * <p>{@link AutoConfigureMockMvc} is declared here next to {@link SpringBootTest} so the test
 * context registers {@link MockMvc}. Keep it co-located for
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

    private static final Logger logger = LoggerFactory.getLogger(AbstractIntegrationTest.class);

    private static final int DYNAMODB_PORT = 8000;

    private static final String DYNAMODB_LOCAL_IMAGE = "amazon/dynamodb-local:latest";

    private static final GenericContainer<?> dynamoDbLocalContainer = createDynamoDbLocalContainer();

    /**
     * Starts DynamoDB Local in shared-db in-memory mode when Docker is available.
     *
     * @return the container instance, running or not depending on Docker availability
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
            logger.warn("Could not start DynamoDB Local container — Docker may not be available. "
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
     * Registers a JVM shutdown hook that stops the DynamoDB Local container.
     *
     * @param container the running Testcontainers instance to close on shutdown
     */
    private static void registerDynamoDbLocalShutdownHook(GenericContainer<?> container) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (container.isRunning()) {
                    container.close();
                }
            } catch (RuntimeException ex) {
                logger.debug("DynamoDB Local shutdown hook: {}", ex.getMessage());
            }
        }, "dynamodb-local-test-shutdown"));
    }

    @Autowired
    private DynamoDbTableTestReset dynamoDbTableTestReset;

    @Autowired
    private DynamoDbAsyncClient dynamoDbAsyncClient;

    @Value("${dynamodb.table-name}")
    private String tableName;

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Skips the test class when the shared DynamoDB Local container failed to start.
     */
    @BeforeAll
    static void ensureDockerAvailable() {
        assumeTrue(dynamoDbLocalContainer.isRunning(),
                "DynamoDB Local container is not running — Docker may not be available. "
                        + "Run with -P rancher-desktop. "
                        + "For Rancher Desktop, ensure DOCKER_HOST=unix://$HOME/.rd/docker.sock.");
    }

    /** Drops, recreates, and re-seeds the table so tests do not share mutated state. */
    @BeforeEach
    void resetDynamoDbTable() {
        dynamoDbTableTestReset.deleteRecreateAndSeed();
    }

    /**
     * Points Spring at the Testcontainers DynamoDB Local endpoint and default test client settings.
     *
     * @param registry dynamic property registry for the test context
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
     * Writes a reservation item under the account partition key for direct DynamoDB setup.
     *
     * @param accountId account identifier without the {@code ACCOUNT#} prefix
     * @param reservationId reservation identifier without the {@code RESERVATION#} prefix
     * @param paymentId linked outbound payment id
     * @param amount reservation amount as a numeric string
     * @param status reservation status value to persist
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
     * Asserts one reservation row in a batch-get-reservations JSON response.
     *
     * @param actions MockMvc result actions containing the response body
     * @param index zero-based index in {@code $.reservations}
     * @param reservationId expected reservation id
     * @param paymentId expected payment id
     * @param amount expected amount
     * @param status expected reservation status
     * @throws Exception when MockMvc JSON path assertions fail
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
     * Asserts multiple reservation rows in order using {@link #assertReservation}.
     *
     * @param actions MockMvc result actions containing the response body
     * @param reservationIds expected reservation ids in response order
     * @param paymentIds expected payment ids aligned with {@code reservationIds}
     * @param amounts expected amounts aligned with {@code reservationIds}
     * @param statuses expected statuses aligned with {@code reservationIds}
     * @throws Exception when list sizes differ or JSON path assertions fail
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
     * Creates an outbound payment via {@code POST /api/v1/payments/outbound} and returns its id.
     *
     * @param debtorAccountId seeded debtor account id
     * @param amount payment amount as a numeric string
     * @param creditorName creditor display name in the request body
     * @return created payment id from the response body
     * @throws Exception when the HTTP request or JSON parsing fails
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
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPathSupport.read(result.getResponse().getContentAsString(), "$.paymentId");
    }

    /**
     * Drives synchronous payment processing via {@code POST .../process}.
     *
     * @param paymentId payment id to process
     * @throws Exception when the HTTP request fails or returns a non-200 status
     */
    protected void processPayment(String paymentId) throws Exception {
        mockMvc.perform(post("/api/v1/payments/outbound/" + paymentId + "/process"))
                .andExpect(status().isOk());
    }

    /**
     * Derives the reservation id convention used when funds are reserved for a payment.
     *
     * @param paymentId outbound payment id
     * @return reservation id of the form {@code res_<paymentId>}
     */
    protected String reservationIdForPayment(String paymentId) {
        return "res_" + paymentId;
    }

    /**
     * Builds a JSON request body for {@code POST /api/v1/accounts/{id}/batch-get-reservations}.
     *
     * @param reservationIds reservation ids to include in {@code reservationIds}
     * @return JSON string suitable for MockMvc request content
     */
    protected String batchGetReservationsRequestBody(List<String> reservationIds) {
        String quotedIds = String.join(",", reservationIds.stream().map(id -> "\"" + id + "\"").toList());
        return "{\"reservationIds\":[" + quotedIds + "]}";
    }

}
