package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.AbstractIntegrationTest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link GlobalExceptionHandler} error handling across {@code /api/v1} endpoints.
 *
 * <p>Tests verify that production controllers and services raise mapped exceptions correctly and
 * that the handler transforms them into documented error envelopes.
 *
 * <p>Coverage includes HTTP layer mappings reachable through real endpoints against DynamoDB Local.
 * Note: The defensive {@code INVALID_BATCH_GET_RESERVATIONS_REQUEST} check stays in unit tests
 * as it is rejected by bean validation before reaching the HTTP layer.
 *
 * <p>Streams are disabled to prevent asynchronous processing races with synchronous error assertions.
 */
@Tag("integration")
@TestPropertySource(properties = "dynamodb.streams.enabled=false")
public class GlobalExceptionHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void getOutboundPayment_whenPaymentMissing_shouldReturn404WithPaymentNotFound() throws Exception {
        String unknownPaymentId = "pay_" + UUID.randomUUID();

        MvcResult result = performAsync(get("/api/v1/payments/outbound/" + unknownPaymentId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andReturn();

        assertErrorResponseSchemaAndValues(
                result,
                "PAYMENT_NOT_FOUND",
                "Payment not found: " + unknownPaymentId);
    }

    @Test
    void getAccount_whenAccountMissing_shouldReturn404WithAccountNotFound() throws Exception {
        MvcResult result = performAsync(get("/api/v1/accounts/acc_nonexistent").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "ACCOUNT_NOT_FOUND", "Account not found: acc_nonexistent");
    }

    @Test
    void createOutboundPayment_whenIdempotencyKeyReusedWithDifferentPayload_shouldReturn409WithIdempotencyConflict()
            throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(idempotencyKey, "acc_usd_1", "100")))
                .andExpect(status().isCreated());

        MvcResult result = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestBody(idempotencyKey, "acc_usd_1", "999")))
                .andExpect(status().isConflict())
                .andReturn();

        assertErrorResponseSchemaAndValues(
                result,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key '" + idempotencyKey + "' was already used with a different request payload");
    }

    @Test
    void listMerchantPaymentsByState_whenStateInvalid_shouldReturn400WithInvalidPaymentState() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments/state/BOGUS")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "INVALID_PAYMENT_STATE", "Invalid payment state: BOGUS");
    }

    @Test
    void listMerchantPayments_whenNextTokenInvalid_shouldReturn400WithInvalidPaginationToken() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments?nextToken=not-base64")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "INVALID_PAGINATION_TOKEN", "Invalid pagination token");
    }

    @Test
    void createOutboundPayment_whenRequiredFieldsMissing_shouldReturn400WithValidationError() throws Exception {
        MvcResult result = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "",
                                  "merchantId": "merch_1",
                                  "debtorAccountId": "acc_usd_1",
                                  "creditorIban": "RO49AAAA1B31007593840000",
                                  "creditorName": "John Doe",
                                  "amount": 10,
                                  "currency": "USD"
                                }"""))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "VALIDATION_ERROR", "idempotencyKey: must not be blank");
    }

    @Test
    void createOutboundPayment_whenBodyMalformedJson_shouldReturn400WithValidationError() throws Exception {
        MvcResult result = performAsync(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "VALIDATION_ERROR", "Request body is not valid JSON");
    }

    @Test
    void listMerchantPayments_whenLimitNotNumeric_shouldReturn400WithValidationError() throws Exception {
        MvcResult result = performAsync(get("/api/v1/merchants/merch_1/payments?limit=abc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertErrorResponseSchemaAndValues(result, "VALIDATION_ERROR", "Invalid value for 'limit': abc");
    }

    @Test
    void getFavicon_whenResourceMissing_shouldReturn204NoContent() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

     /**
      * Validates the error-response contract to ensure schema and values remain stable.
      *
      * <p>Asserts the JSON object contains exactly three required fields: {@code error},
      * {@code message}, and {@code timestamp}. All fields are verified to be textual values
      * with expected content. The timestamp format is validated for plausibility.
      *
      * @param result MVC response to validate
      * @param expectedError machine-readable error code
      * @param expectedMessage human-readable error message
      */
    private static void assertErrorResponseSchemaAndValues(
            MvcResult result,
            String expectedError,
            String expectedMessage) throws Exception {
        assertThat(result.getResponse().getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);

        String body = result.getResponse().getContentAsString();
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.isObject()).isTrue();
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("error")).isTrue();
        assertThat(json.has("message")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("error").isTextual()).isTrue();
        assertThat(json.get("message").isTextual()).isTrue();
        assertThat(json.get("timestamp").isTextual()).isTrue();
        assertThat(json.get("error").asText()).isEqualTo(expectedError);
        assertThat(json.get("message").asText()).isEqualTo(expectedMessage);
        JsonPathSupport.readInstantAssertingPlausibleNow(body, "$.timestamp");
    }

     /**
      * Builds a valid JSON request body for creating an outbound payment.
      *
      * <p>Example:
      * <pre>
      * {
      *   "idempotencyKey": "key-uuid",
      *   "merchantId": "merch_1",
      *   "debtorAccountId": "acc_usd_1",
      *   "creditorIban": "RO49AAAA1B31007593840000",
      *   "creditorName": "John Doe",
      *   "amount": 100,
      *   "currency": "USD"
      * }
      * </pre>
      *
      * @param idempotencyKey unique request identifier
      * @param debtorAccountId source account identifier
      * @param amount payment amount as numeric string
      * @return formatted JSON request body
      */
    private static String createRequestBody(String idempotencyKey, String debtorAccountId, String amount) {
        return """
                {
                  "idempotencyKey": "%s",
                  "merchantId": "merch_1",
                  "debtorAccountId": "%s",
                  "creditorIban": "RO49AAAA1B31007593840000",
                  "creditorName": "John Doe",
                  "amount": %s,
                  "currency": "USD"
                 }""".formatted(idempotencyKey, debtorAccountId, amount);
     }
}
