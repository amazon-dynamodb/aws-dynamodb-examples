package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies the module README documents the DynamoDB Streams operational notes.
 *
 * <p>It guards the restart-gap recovery guidance and the in-code filter trade-off so the notes
 * cannot silently disappear from {@code README.md}.
 */
@Tag("unit")
class ReadmeStreamsNotesTest {

    private static final Path README = Path.of("README.md");

    @Test
    void readme_whenDescribingStreams_shouldDocumentTrimHorizonRecovery() throws IOException {
        assertThat(readReadme())
                .as("README should document TRIM_HORIZON as the restart replay option")
                .contains("TRIM_HORIZON");
    }

    @Test
    void readme_whenDescribingStreams_shouldDocumentServerSideFilteringAlternative() throws IOException {
        assertThat(readReadme())
                .as("README should mention Kinesis Data Streams server-side filtering")
                .contains("Kinesis Data Streams");
    }

    /**
     * Reads the module README as text.
     *
     * @return README content
     * @throws IOException if the file cannot be read
     */
    private static String readReadme() throws IOException {
        assertThat(Files.exists(README))
                .as("expected README at module-relative path: %s", README)
                .isTrue();
        return Files.readString(README);
    }
}

