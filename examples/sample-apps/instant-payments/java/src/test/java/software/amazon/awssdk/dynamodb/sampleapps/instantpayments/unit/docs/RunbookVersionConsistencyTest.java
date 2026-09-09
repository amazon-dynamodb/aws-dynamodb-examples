package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards against documentation drift: the versions advertised in {@code docs/Runbook.md} must match
 * the authoritative versions declared in {@code pom.xml}. Catches the kind of Spring Boot / AWS SDK
 * version skew that compounds when the runbook is updated independently of the build.
 */
@Tag("unit")
class RunbookVersionConsistencyTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path RUNBOOK = Path.of("docs", "Runbook.md");

    @Test
    void runbook_whenComparedToPom_shouldAdvertiseSpringBootVersion() throws IOException {
        String springBootVersion = matchGroup(
                readFile(POM), "<parent>.*?<version>(.*?)</version>");
        assertThat(readFile(RUNBOOK))
                .as("Runbook must list the Spring Boot version declared in pom.xml")
                .contains(springBootVersion);
    }

    @Test
    void runbook_whenComparedToPom_shouldAdvertiseAwsSdkVersion() throws IOException {
        String awsSdkVersion = matchGroup(readFile(POM), "<aws-sdk-v2\\.version>(.*?)</aws-sdk-v2\\.version>");
        assertThat(readFile(RUNBOOK))
                .as("Runbook must list the AWS SDK v2 BOM version declared in pom.xml")
                .contains(awsSdkVersion);
    }

    @Test
    void runbook_whenComparedToPom_shouldAdvertiseSpringdocVersion() throws IOException {
        String springdocVersion = matchGroup(readFile(POM), "<springdoc-openapi\\.version>(.*?)</springdoc-openapi\\.version>");
        assertThat(readFile(RUNBOOK))
                .as("Runbook must list the springdoc-openapi version declared in pom.xml")
                .contains(springdocVersion);
    }

    @Test
    void runbook_whenComparedToPom_shouldAdvertiseTestcontainersVersion() throws IOException {
        String testcontainersVersion = matchGroup(readFile(POM), "<testcontainers\\.version>(.*?)</testcontainers\\.version>");
        assertThat(readFile(RUNBOOK))
                .as("Runbook must list the Testcontainers version declared in pom.xml")
                .contains(testcontainersVersion);
    }

    /**
     * Reads file content from a module-relative path.
     *
     * <p>Verifies file exists before reading to provide clear error if missing.
     *
     * @param path module-relative file path
     * @return file content as string
     * @throws IOException if file cannot be read
     */
    private static String readFile(Path path) throws IOException {
        assertThat(Files.exists(path))
                .as("expected file to exist at module-relative path: %s", path)
                .isTrue();
        return Files.readString(path);
    }

    /**
     * Extracts first regex capture group from text.
     *
     * <p>Verifies pattern matches before extraction for clear error messaging.
     *
     * @param content text to search
     * @param regex pattern with capture group
     * @return first capture group trimmed
     */
    private static String matchGroup(String content, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(content);
        assertThat(matcher.find())
                .as("expected pom.xml to declare a version matching: %s", regex)
                .isTrue();
        return matcher.group(1).trim();
    }
}

