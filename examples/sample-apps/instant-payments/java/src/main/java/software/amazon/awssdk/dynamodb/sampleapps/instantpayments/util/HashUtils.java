package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cryptographic hashing utility for idempotency key management.
 */
public final class HashUtils {

    /** Utility class, not instantiated. */
    private HashUtils() {
    }

    /**
     * Computes the SHA-256 hash of the given input string.
     *
     * @param input the string to hash
     * @return lowercase hexadecimal digest
     * @throws IllegalStateException if SHA-256 is not available (should never happen on standard JVMs)
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
