package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Shared Jayway {@link JsonPath} utilities for reading and asserting on JSON response bodies
 * produced by MockMvc-driven integration, smoke, and controller unit tests, including common
 * instant-payments identifier and timestamp checks.
 */
public final class JsonPathSupport {

    /** Prevents instantiation of static helpers. */
    private JsonPathSupport() {
    }

    /**
     * Reads a typed value from JSON using Jayway JsonPath.
     *
     * @param json response or request body as JSON text
     * @param path JsonPath expression
     * @param <T> expected result type inferred at the call site
     * @return value at {@code path}
     */
    public static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /**
     * Returns the size of a JSON array selected by JsonPath.
     *
     * @param json response body as JSON text
     * @param path JsonPath to an array node
     * @return number of elements in the selected array
     */
    public static int arraySize(String json, String path) {
        List<?> list = JsonPath.read(json, path);
        return list.size();
    }

    /**
     * Parses an ISO-8601 instant from JSON and asserts it is near the current clock time.
     *
     * @param json response body as JSON text
     * @param path JsonPath to the instant string field
     * @return parsed instant that passed plausibility checks
     */
    public static Instant readInstantAssertingPlausibleNow(String json, String path) {
        String raw = read(json, path);
        Instant i = Instant.parse(raw);
        Instant now = Instant.now();
        assertThat(i).isAfter(now.minus(Duration.ofMinutes(5)));
        assertThat(i).isBeforeOrEqualTo(now.plus(Duration.ofSeconds(5)));
        return i;
    }

    /**
     * Asserts a payment id uses the {@code pay_} prefix followed by a UUID.
     *
     * @param paymentId payment id from an API response
     */
    public static void assertLogicalPaymentId(String paymentId) {
        assertThat(paymentId).startsWith("pay_");
        UUID.fromString(paymentId.substring(4));
    }

    /**
     * Asserts a correlation id uses the {@code corr_} prefix followed by a UUID.
     *
     * @param correlationId correlation id from an API response
     */
    public static void assertLogicalCorrelationId(String correlationId) {
        assertThat(correlationId).startsWith("corr_");
        UUID.fromString(correlationId.substring(5));
    }
}
