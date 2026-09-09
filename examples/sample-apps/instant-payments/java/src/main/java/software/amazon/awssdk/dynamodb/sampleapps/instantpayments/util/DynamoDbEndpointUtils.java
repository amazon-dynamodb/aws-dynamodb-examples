package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.net.URI;

/**
 * Utility for detecting whether a DynamoDB endpoint URL refers to a local instance
 * (e.g. DynamoDB Local) versus real AWS DynamoDB.
 *
 * <p>Used to decide credential and initialization behavior. Local endpoints use
 * fake credentials and may run table creation. AWS endpoints use the default credential chain.
 */
public final class DynamoDbEndpointUtils {

    /** Utility class, not instantiated. */
    private DynamoDbEndpointUtils() {
    }

    /**
     * Determines if the given endpoint URL points to a local DynamoDB instance.
     *
     * <p>Returns {@code true} when the host is {@code localhost}, {@code 127.0.0.1},
     * {@code dynamodb}, or {@code dynamodb-local} (common Docker service names).
     *
     * @param endpointUrl the endpoint URL (e.g. {@code http://localhost:8000})
     * @return {@code true} if the endpoint is local, {@code false} otherwise or on parse error
     */
    public static boolean isLocalEndpoint(String endpointUrl) {
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("dynamodb")
                    || host.equals("dynamodb-local");
        } catch (Exception e) {
            return false;
        }
    }
}
