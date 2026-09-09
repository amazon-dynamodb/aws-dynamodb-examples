package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.exception;

/**
 * Raised when a pagination {@code nextToken} cannot be decoded for the current route, the decoded
 * key lacks the route-specific discriminator, the GSI partition attribute does not match the path
 * user, or the encoded page depth is at or above {@code dynamodb.max-pages-allowed}.
 *
 * <p>Maps to HTTP {@code 400} with error code {@code INVALID_PAGINATION_TOKEN}. Tokens are
 * route-specific and bound to the path user: a token issued by one list endpoint or minted for
 * another user must not be accepted on this request.
 */
public class InvalidPaginationTokenException extends RuntimeException {

    /**
     * Creates the exception for a malformed, wrong-route, or wrong-owner token.
     *
     * @param nextToken the offending opaque token
     */
    public InvalidPaginationTokenException(String nextToken) {
        super("Invalid pagination token: " + nextToken);
    }

    /**
     * Creates the exception for a token that failed to decode, preserving the underlying cause.
     *
     * @param nextToken the offending opaque token
     * @param cause     the decoding failure
     */
    public InvalidPaginationTokenException(String nextToken, Throwable cause) {
        super("Invalid pagination token: " + nextToken, cause);
    }
}
