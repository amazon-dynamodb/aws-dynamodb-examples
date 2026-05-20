package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.util.HashUtils;

/**
 * Unit tests for {@link HashUtils}.
 */
@Tag("unit")
public class HashUtilsTest {

    @Test
    void sha256_whenKnownValueProvided_shouldMatchExpectedHexDigest() {
        assertThat(HashUtils.sha256("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sha256_whenEmptyStringProvided_shouldReturnDeterministic64CharHex() {
        assertThat(HashUtils.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
