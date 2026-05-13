package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.AccountNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.IdempotencyConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidBatchGetReservationsRequestException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;

/**
 * Web MVC slice tests for {@link GlobalExceptionHandler} using a throwaway controller to verify
 * exception-to-response mapping and error payload timestamps.
 */
@Tag("unit")
@WebMvcTest(GlobalExceptionHandlerTest.ExceptionThrowingController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ExceptionThrowingController.class})
public class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void paymentNotFound_returns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/payment-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Payment not found: pay_x"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void accountNotFound_returns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/account-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: acc_z"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void idempotencyConflict_returns409() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/idempotency-conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency key 'idem_clash' was already used with a different request payload"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void invalidBatchGetReservationsRequest_returns400() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/invalid-batch-get-reservations")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BATCH_GET_RESERVATIONS_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("reservationIds must contain at least one distinct reservation id"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void unexpectedException_returns500() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @RestController
    public static class ExceptionThrowingController {

        @GetMapping("/test/payment-not-found")
        void paymentNotFound() {
            throw new PaymentNotFoundException("pay_x");
        }

        @GetMapping("/test/account-not-found")
        void accountNotFound() {
            throw new AccountNotFoundException("acc_z");
        }

        @GetMapping("/test/idempotency-conflict")
        void idempotencyConflict() {
            throw new IdempotencyConflictException("idem_clash");
        }

        @GetMapping("/test/invalid-batch-get-reservations")
        void invalidBatchGetReservations() {
            throw new InvalidBatchGetReservationsRequestException(
                    "reservationIds must contain at least one distinct reservation id");
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom");
        }
    }
}
