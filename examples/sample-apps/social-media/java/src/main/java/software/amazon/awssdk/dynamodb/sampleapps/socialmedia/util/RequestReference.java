package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Holds the per-request reference id used to correlate a generic {@code INTERNAL_ERROR} with the
 * matching server log line.
 *
 * <p>The id is stored in MDC and as a request attribute. An incoming {@code X-Request-Id} is reused
 * when it matches the allowed pattern. Otherwise a UUID is generated.
 */
public final class RequestReference {

    public static final String HEADER_NAME = "X-Request-Id";

    public static final String MDC_KEY = "request-id";

    public static final String REQUEST_ATTRIBUTE = RequestReference.class.getName();

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    /** Utility class, not instantiated. */
    private RequestReference() {
    }

    /**
     * Returns whether {@code value} is safe to reuse as a request reference id.
     *
     * @param value candidate id, possibly null
     * @return {@code true} when the value matches the allowed pattern
     */
    public static boolean isValid(String value) {
        return value != null && VALID_ID.matcher(value).matches();
    }

    /**
     * Reuses a valid incoming id or generates a UUID.
     *
     * @param incoming header value, possibly null or blank
     * @return a reference id safe to embed in logs and the generic error message
     */
    public static String resolveIncomingOrGenerate(String incoming) {
        if (isValid(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the reference id for the current request.
     *
     * <p>Prefers MDC, then the request attribute. Generates a UUID when neither is present so the
     * generic error path still returns a correlatable token.
     *
     * @return the current request reference id
     */
    public static String current() {
        String fromMdc = MDC.get(MDC_KEY);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object value = attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (value instanceof String id && !id.isBlank()) {
                return id;
            }
        }
        return UUID.randomUUID().toString();
    }
}
