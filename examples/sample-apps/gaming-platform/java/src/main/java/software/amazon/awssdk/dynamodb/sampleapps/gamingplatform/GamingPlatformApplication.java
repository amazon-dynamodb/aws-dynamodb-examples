package software.amazon.awssdk.dynamodb.sampleapps.gamingplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the gaming-platform Spring Boot application.
 *
 * <p>This Spring Boot application exposes HTTP APIs backed by DynamoDB for player state,
 * events, lobbies, and leaderboards.
 *
 * <p>Required configuration properties:
 * <ul>
 *   <li>{@code dynamodb.endpoint}: full endpoint URL (for example {@code http://localhost:8000} or the AWS endpoint)</li>
 *   <li>{@code dynamodb.region}: AWS region (for example {@code eu-west-1})</li>
 *   <li>{@code dynamodb.client-type}: {@code high-level} or {@code low-level}</li>
 * </ul>
 */
@SpringBootApplication
public class GamingPlatformApplication {

    /**
     * Application entry point.
     *
     * @param args command-line arguments (e.g. {@code --dynamodb.endpoint=...})
     */
    public static void main(String[] args) {
        SpringApplication.run(GamingPlatformApplication.class, args);
    }
}
