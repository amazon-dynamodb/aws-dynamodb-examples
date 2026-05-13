package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

/**
 * Jayway {@link JsonPath} helpers for parsing {@link org.springframework.mock.web.MockHttpServletResponse}
 * bodies in integration, smoke, and controller unit tests (MockMvc), plus small API-contract assertions.
 */
public final class JsonPathSupport {

    private JsonPathSupport() {
    }

    /**
     * Reads a value using a JsonPath expression (e.g. {@code "$.paymentId"}).
     *
     * @param json response body text
     * @param path JsonPath query
     * @param <T>  expected type of the matched value
     * @return value at {@code path}
     */
    public static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    /**
     * Returns the number of elements in a JSON array at {@code path}.
     *
     * @param json response body text
     * @param path JsonPath to an array (e.g. {@code "$.events"})
     * @return array length
     */
    public static int arraySize(String json, String path) {
        List<?> list = JsonPath.read(json, path);
        return list.size();
    }

    /**
     * Reads an ISO-8601 instant at {@code path}, parses it, and asserts (via AssertJ) that it falls
     * within a small window before and slightly after {@link Instant#now()} — rejects bogus or
     * stale default timestamps while still allowing minor clock skew.
     *
     * @param json JSON text
     * @param path JsonPath to an instant serialized as a string (e.g. {@code "$.timestamp"})
     * @return the parsed instant
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
     * Asserts {@code paymentId} matches the server format {@code pay_}{@link UUID}.
     *
     * @param paymentId value from create-payment responses or stream heads
     */
    public static void assertLogicalPaymentId(String paymentId) {
        assertThat(paymentId).startsWith("pay_");
        UUID.fromString(paymentId.substring(4));
    }

    /**
     * Asserts {@code correlationId} matches the server format {@code corr_}{@link UUID}.
     *
     * @param correlationId value from create-payment responses
     */
    public static void assertLogicalCorrelationId(String correlationId) {
        assertThat(correlationId).startsWith("corr_");
        UUID.fromString(correlationId.substring(5));
    }
}
