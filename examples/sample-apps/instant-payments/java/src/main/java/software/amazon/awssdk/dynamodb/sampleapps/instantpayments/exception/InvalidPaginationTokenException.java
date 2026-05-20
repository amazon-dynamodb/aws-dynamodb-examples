package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception;

/**
 * Thrown when a client-supplied pagination token cannot be decoded into a DynamoDB exclusive-start key.
 *
 * <p>Maps to HTTP 400 Bad Request with error code {@code INVALID_PAGINATION_TOKEN}. Handled by
 * {@link GlobalExceptionHandler}.
 */
public class InvalidPaginationTokenException extends RuntimeException {

    /** Opaque pagination token supplied by the client that could not be decoded. */
    private final String nextToken;

    /**
     * @param nextToken invalid opaque token supplied by the client
     */
    public InvalidPaginationTokenException(String nextToken) {
        super("Invalid pagination token");
        this.nextToken = nextToken;
    }

    /**
     * @param nextToken invalid opaque token supplied by the client
     * @param cause     decode failure cause
     */
    public InvalidPaginationTokenException(String nextToken, Throwable cause) {
        super("Invalid pagination token", cause);
        this.nextToken = nextToken;
    }

    public String getNextToken() {
        return nextToken;
    }
}
