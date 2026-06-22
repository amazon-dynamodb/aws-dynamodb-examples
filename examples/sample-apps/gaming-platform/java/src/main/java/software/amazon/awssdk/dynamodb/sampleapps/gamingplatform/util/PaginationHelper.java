package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.util;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Encodes and decodes opaque pagination tokens for DynamoDB {@code ExclusiveStartKey}
 * and {@code LastEvaluatedKey} maps.
 *
 * <p>Each {@code LastEvaluatedKey} map is serialised to JSON then base64url-encoded without padding
 * so it can be passed safely as a query parameter.
 *
 * <p>Only {@code S}-type attribute values are supported, which is sufficient for the
 * GameEvents table whose keys {@code PK} and {@code SK} are both strings.
 */
public final class PaginationHelper {

    /** Shared JSON mapper for pagination token encodings. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Jackson type reference for the intermediate JSON shape during encode and decode. */
    private static final TypeReference<Map<String, Map<String, String>>> CURSOR_TYPE =
            new TypeReference<>() { };

    /** Not instantiated. */
    private PaginationHelper() {
    }

    /**
     * Encodes a DynamoDB {@code LastEvaluatedKey} map to an opaque base64url {@code nextToken} string.
     *
     * @param lastEvaluatedKey the key map returned by a DynamoDB query, may be {@code null}
     * @return the encoded token, or {@code null} if the input is {@code null} or empty
     * @throws IllegalStateException if the key map cannot be serialised to JSON
     */
    public static String encodePaginationToken(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }

        // Wrap each string attribute in the DynamoDB JSON {"S": "value"} shape.
        Map<String, Map<String, String>> json = new LinkedHashMap<>();
        lastEvaluatedKey.forEach((key, av) -> json.put(key, Map.of("S", av.s())));

        try {
            byte[] bytes = MAPPER.writeValueAsBytes(json);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode pagination token", e);
        }
    }

    /**
     * Decodes an opaque {@code nextToken} string back to a DynamoDB {@code ExclusiveStartKey} map
     * without binding it to a partition.
     *
     * @param nextToken the token string, may be {@code null} or blank
     * @return the decoded key map, or {@code null} if the token is {@code null} or blank
     * @throws InvalidPaginationTokenException if the token is malformed or missing required keys
     */
    public static Map<String, AttributeValue> decodePaginationToken(String nextToken) {
        return decodePaginationToken(nextToken, null);
    }

    /**
     * Decodes an opaque {@code nextToken} string back to a DynamoDB {@code ExclusiveStartKey} map and
     * binds it to the caller's partition.
     *
     * <p>The decoded {@code PK} must equal {@code expectedPartitionKey}. This stops a token minted for
     * one player's query from being replayed against another player's path. Without the check, a
     * mismatched {@code ExclusiveStartKey} reaches DynamoDB and is rejected as a server error, which
     * surfaces to the client as a 500. Failing here instead yields a clean 400.
     *
     * @param nextToken            the token string, may be {@code null} or blank
     * @param expectedPartitionKey the partition key the token must carry, or {@code null} to skip the
     *                             binding check
     * @return the decoded key map, or {@code null} if the token is {@code null} or blank
     * @throws InvalidPaginationTokenException if the token is malformed, missing required keys, or its
     *                                         {@code PK} does not match {@code expectedPartitionKey}
     */
    public static Map<String, AttributeValue> decodePaginationToken(String nextToken, String expectedPartitionKey) {
        if (nextToken == null || nextToken.isBlank()) {
            return null;
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(nextToken);
        } catch (IllegalArgumentException e) {
            throw new InvalidPaginationTokenException(nextToken, e);
        }

        Map<String, Map<String, String>> json;
        try {
            json = MAPPER.readValue(decoded, CURSOR_TYPE);
        } catch (Exception e) {
            throw new InvalidPaginationTokenException(nextToken, e);
        }

        // GameEvents table keys require both PK and SK attributes.
        if (!json.containsKey("PK") || !json.containsKey("SK")) {
            throw new InvalidPaginationTokenException(nextToken);
        }

        // Bind the token to the caller's partition so a foreign token fails fast as a 400.
        if (expectedPartitionKey != null && !expectedPartitionKey.equals(json.get("PK").get("S"))) {
            throw new InvalidPaginationTokenException(nextToken);
        }

        Map<String, AttributeValue> key = new LinkedHashMap<>();
        json.forEach((k, v) -> key.put(k, AttributeValue.fromS(v.get("S"))));
        return key;
    }
}
