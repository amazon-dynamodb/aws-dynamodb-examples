package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller.AccountController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.BatchGetReservationsResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetAccountResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ReservationResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.AccountNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidBatchGetReservationsRequestException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.AccountQueryService;

/**
 * Unit tests for {@link AccountController}.
 */
@Tag("unit")
@WebMvcTest(AccountController.class)
@Import(GlobalExceptionHandler.class)
public class AccountControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AccountQueryService accountQueryService;

    @Test
    void batchGetReservations_success_returns200() throws Exception {
        Instant created = Instant.parse("2026-03-18T10:15:33Z");
        BatchGetReservationsResponse response = new BatchGetReservationsResponse(
                List.of(new ReservationResponse("res_1", "pay_1", new BigDecimal("100"), "ACTIVE", created)),
                List.of("res_missing"));

        when(accountQueryService.batchGetReservations(eq("acc_usd_1"), any())).thenReturn(response);

        mvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[\"res_1\",\"res_missing\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationId").value("res_1"))
                .andExpect(jsonPath("$.reservations[0].paymentId").value("pay_1"))
                .andExpect(jsonPath("$.reservations[0].amount").value(100))
                .andExpect(jsonPath("$.reservations[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.missingReservationIds.length()").value(1))
                .andExpect(jsonPath("$.missingReservationIds[0]").value("res_missing"));
    }

    @Test
    void batchGetReservations_emptyList_returns400() throws Exception {
        mvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_tooManyIds_returns400() throws Exception {
        String ids = IntStream.range(0, 101)
                .mapToObj(i -> "\"res_" + i + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        mvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_blankId_returns400() throws Exception {
        mvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[\"res_1\",\"  \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void batchGetReservations_serviceRejectsRequest_returns400() throws Exception {
        when(accountQueryService.batchGetReservations(eq("acc_usd_1"), any()))
                .thenThrow(new InvalidBatchGetReservationsRequestException(
                        "reservationIds must contain at least one distinct reservation id"));

        mvc.perform(post("/api/v1/accounts/acc_usd_1/batch-get-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservationIds\":[\"res_1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BATCH_GET_RESERVATIONS_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "reservationIds must contain at least one distinct reservation id"));
    }

    @Test
    void getAccount_success_returns200() throws Exception {
        Instant resCreated = Instant.parse("2026-03-18T10:15:33Z");
        GetAccountResponse response = new GetAccountResponse(
                "acc_usd_1",
                "ACTIVE",
                "USD",
                new BigDecimal("10000"),
                new BigDecimal("9900"),
                List.of(new ReservationResponse(
                        "res_pay_1", "pay_1", new BigDecimal("100"), "ACTIVE", resCreated)));

        when(accountQueryService.getAccount("acc_usd_1")).thenReturn(response);

        mvc.perform(get("/api/v1/accounts/acc_usd_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acc_usd_1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.currentBalance").value(10000))
                .andExpect(jsonPath("$.availableBalance").value(9900))
                .andExpect(jsonPath("$.reservations.length()").value(1))
                .andExpect(jsonPath("$.reservations[0].reservationId").value("res_pay_1"))
                .andExpect(jsonPath("$.reservations[0].paymentId").value("pay_1"))
                .andExpect(jsonPath("$.reservations[0].amount").value(100))
                .andExpect(jsonPath("$.reservations[0].status").value("ACTIVE"));
    }

    @Test
    void getAccount_notFound_returns404() throws Exception {
        when(accountQueryService.getAccount("acc_nonexistent"))
                .thenThrow(new AccountNotFoundException("acc_nonexistent"));

        MvcResult result = mvc.perform(get("/api/v1/accounts/acc_nonexistent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: acc_nonexistent"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void getAccount_unexpectedError_returns500() throws Exception {
        when(accountQueryService.getAccount("acc_boom"))
                .thenThrow(new RuntimeException("downstream failure"));

        MvcResult result = mvc.perform(get("/api/v1/accounts/acc_boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }
}
