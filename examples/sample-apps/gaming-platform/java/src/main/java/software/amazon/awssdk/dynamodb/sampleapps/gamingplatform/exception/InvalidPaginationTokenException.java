package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception;

/**
 * Thrown when a client-supplied pagination token cannot be decoded into a DynamoDB exclusive-start key.
 *
 * <p>Maps to HTTP 400 Bad Request with error code {@code INVALID_PAGINATION_TOKEN}.
 * Handled by {@link GlobalExceptionHandler}.
 */
public class InvalidPaginationTokenException extends RuntimeException {

    /**
     * Creates an exception for an invalid token without a cause.
     *
     * @param nextToken invalid opaque token supplied by the client
     */
    public InvalidPaginationTokenException(String nextToken) {
        super("Invalid pagination token: " + nextToken);
    }

    /**
     * Creates an exception wrapping a decode failure.
     *
     * @param nextToken invalid opaque token supplied by the client
     * @param cause     decode failure cause
     */
    public InvalidPaginationTokenException(String nextToken, Throwable cause) {
        super("Invalid pagination token: " + nextToken, cause);
    }
}
