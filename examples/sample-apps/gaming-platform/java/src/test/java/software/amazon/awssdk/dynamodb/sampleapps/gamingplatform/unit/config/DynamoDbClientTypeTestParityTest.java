package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guardrail ensuring every integration and smoke parent test class has a matching
 * {@code *LowLevel*} subclass that inherits the same {@code @Test} methods.
 *
 * <p>Scans integration and smoke test sources on the filesystem.
 */
@Tag("unit")
class DynamoDbClientTypeTestParityTest {

    private static final Path TEST_ROOT = Path.of(
            "src/test/java/software/amazon/awssdk/dynamodb/sampleapps/gamingplatform");

    @Test
    void findMissingLowLevelCounterparts_whenIntegrationAndSmokeScanned_shouldBeEmpty() throws IOException {
        List<String> missing = new ArrayList<>();
        missing.addAll(findMissingLowLevelCounterparts("integration"));
        missing.addAll(findMissingLowLevelCounterparts("smoke"));
        assertThat(missing)
                .as("Parent test classes without a *LowLevel* mirror")
                .isEmpty();
    }

    /**
     * Collects high-level test class names missing a low-level counterpart.
     */
    private static List<String> findMissingLowLevelCounterparts(String tier) throws IOException {
        Path root = TEST_ROOT.resolve(tier);
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith("Test.java"))
                    .map(path -> path.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .filter(name -> !name.startsWith("Abstract"))
                    .filter(name -> !name.contains("LowLevel"))
                    .forEach(parentName -> {
                        String lowLevelName = toLowLevelName(parentName);
                        Path lowLevelPath = findTestFile(root, lowLevelName);
                        if (lowLevelPath == null) {
                            missing.add(tier + "/" + parentName);
                        }
                    });
        }
        return missing;
    }

    /**
     * Derives the expected low-level test class name from a high-level name.
     */
    private static String toLowLevelName(String parentName) {
        if (parentName.endsWith("IntegrationTest")) {
            return parentName.replace("IntegrationTest", "LowLevelIntegrationTest");
        }
        if (parentName.endsWith("SmokeTest")) {
            return parentName.replace("SmokeTest", "LowLevelSmokeTest");
        }
        return parentName + "LowLevel";
    }

    /**
     * Locates a test source file under the module test tree.
     */
    private static Path findTestFile(Path root, String className) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.getFileName().toString().equals(className + ".java"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan test sources under " + root, e);
        }
    }
}
