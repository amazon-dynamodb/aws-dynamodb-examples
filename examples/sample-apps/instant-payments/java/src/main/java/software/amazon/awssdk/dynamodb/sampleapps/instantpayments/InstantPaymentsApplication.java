package software.amazon.awssdk.dynamodb.sampleapps.instantpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Instant Payments DynamoDB Sample Application.
 *
 * <p>This application demonstrates DynamoDB patterns for instant payments including:
 * <ul>
 *   <li>Idempotent payment creation via conditional writes</li>
 *   <li>Atomic financial operations via TransactWriteItems</li>
 *   <li>Safe state transitions via condition expressions</li>
 *   <li>Item collection pattern for efficient queries</li>
 * </ul>
 *
 * <p>Required configuration properties:
 * <ul>
 *   <li>{@code dynamodb.endpoint}: full endpoint URL (e.g. {@code http://localhost:8000} or AWS)</li>
 *   <li>{@code dynamodb.region}: AWS region (e.g. {@code eu-west-1})</li>
 *   <li>{@code dynamodb.client-type}: {@code high-level} or {@code low-level}</li>
 * </ul>
 */
@SpringBootApplication
public class InstantPaymentsApplication {

    /**
     * Application entry point.
     *
     * @param args command-line arguments (e.g. {@code --dynamodb.endpoint=...})
     */
    public static void main(String[] args) {
        SpringApplication.run(InstantPaymentsApplication.class, args);
    }
}
