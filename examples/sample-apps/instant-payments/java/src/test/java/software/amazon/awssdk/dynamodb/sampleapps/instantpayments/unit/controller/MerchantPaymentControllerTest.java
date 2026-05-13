package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller.MerchantPaymentController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentProjection;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.MerchantPaymentsPage;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaginationTokenException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.InvalidPaymentStateException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.integration.utils.JsonPathSupport;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.MerchantPaymentQueryService;

/**
 * Unit tests for {@link MerchantPaymentController}.
 *
 * <p>Verifies HTTP status codes, JSON response shapes, pagination query parameters, and error
 * handling via {@link GlobalExceptionHandler}.
 */
@Tag("unit")
@WebMvcTest(MerchantPaymentController.class)
@Import(GlobalExceptionHandler.class)
public class MerchantPaymentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MerchantPaymentQueryService merchantPaymentQueryService;

    @Test
    void listMerchantPayments_success_returns200WithPageEnvelope() throws Exception {
        MerchantPaymentProjection p1 = new MerchantPaymentProjection(
                "pay_1", "COMPLETED", 3, "merch_1", "corr_1",
                new BigDecimal("100.00"), "USD",
                Instant.parse("2026-03-18T10:15:30Z"),
                Instant.parse("2026-03-18T10:15:41Z"), null);
        MerchantPaymentProjection p2 = new MerchantPaymentProjection(
                "pay_2", "RECEIVED", 1, "merch_1", "corr_2",
                new BigDecimal("250.00"), "EUR",
                Instant.parse("2026-03-18T10:16:00Z"),
                Instant.parse("2026-03-18T10:16:05Z"), "INSUFFICIENT_FUNDS");

        when(merchantPaymentQueryService.listMerchantPayments(eq("merch_1"), isNull(), isNull(), isNull()))
                .thenReturn(new MerchantPaymentsPage(List.of(p1, p2), "token-1"));

        mvc.perform(get("/api/v1/merchants/merch_1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextToken").value("token-1"))
                .andExpect(jsonPath("$.items[0].paymentId").value("pay_1"))
                .andExpect(jsonPath("$.items[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].merchantId").value("merch_1"))
                .andExpect(jsonPath("$.items[0].correlationId").value("corr_1"))
                .andExpect(jsonPath("$.items[0].amount").value(100.00))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].createdAtUtc").value("2026-03-18T10:15:30Z"))
                .andExpect(jsonPath("$.items[0].updatedAtUtc").value("2026-03-18T10:15:41Z"))
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.items[1].paymentId").value("pay_2"))
                .andExpect(jsonPath("$.items[1].reasonCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void listMerchantPayments_scanIndexForwardAndNextToken_delegateToService() throws Exception {
        when(merchantPaymentQueryService.listMerchantPayments(eq("merch_1"), isNull(), eq(true), eq("token-1")))
                .thenReturn(new MerchantPaymentsPage(List.of(), null));

        mvc.perform(get("/api/v1/merchants/merch_1/payments?scanIndexForward=true&nextToken=token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextToken").doesNotExist());

        verify(merchantPaymentQueryService).listMerchantPayments(eq("merch_1"), isNull(), eq(true), eq("token-1"));
    }

    @Test
    void listMerchantPayments_empty_returns200WithEmptyItems() throws Exception {
        when(merchantPaymentQueryService.listMerchantPayments(eq("merch_1"), isNull(), isNull(), isNull()))
                .thenReturn(new MerchantPaymentsPage(List.of(), null));

        mvc.perform(get("/api/v1/merchants/merch_1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }

    @Test
    void listMerchantPayments_invalidNextToken_returns400() throws Exception {
        when(merchantPaymentQueryService.listMerchantPayments(eq("merch_1"), isNull(), isNull(), eq("bad-token")))
                .thenThrow(new InvalidPaginationTokenException("bad-token"));

        MvcResult result = mvc.perform(get("/api/v1/merchants/merch_1/payments?nextToken=bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid pagination token"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void listMerchantPayments_crossGsiToken_returns400() throws Exception {
        when(merchantPaymentQueryService.listMerchantPayments(eq("merch_1"), isNull(), isNull(), eq("gsi-merchant-state-payments-token")))
                .thenThrow(new InvalidPaginationTokenException("gsi-merchant-state-payments-token"));

        mvc.perform(get("/api/v1/merchants/merch_1/payments?nextToken=gsi-merchant-state-payments-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPaymentsByState_success_returns200() throws Exception {
        MerchantPaymentProjection p1 = new MerchantPaymentProjection(
                "pay_1", "COMPLETED", 3, "merch_1", "corr_1",
                new BigDecimal("100.00"), "USD",
                Instant.parse("2026-03-18T10:15:30Z"),
                Instant.parse("2026-03-18T10:15:41Z"), null);

        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("COMPLETED"), isNull(), isNull(), isNull()))
                .thenReturn(new MerchantPaymentsPage(List.of(p1), "token-2"));

        mvc.perform(get("/api/v1/merchants/merch_1/payments/state/COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextToken").value("token-2"))
                .andExpect(jsonPath("$.items[0].paymentId").value("pay_1"))
                .andExpect(jsonPath("$.items[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].version").value(3))
                .andExpect(jsonPath("$.items[0].merchantId").value("merch_1"))
                .andExpect(jsonPath("$.items[0].correlationId").value("corr_1"))
                .andExpect(jsonPath("$.items[0].amount").value(100.00))
                .andExpect(jsonPath("$.items[0].currency").value("USD"))
                .andExpect(jsonPath("$.items[0].createdAtUtc").value("2026-03-18T10:15:30Z"))
                .andExpect(jsonPath("$.items[0].updatedAtUtc").value("2026-03-18T10:15:41Z"))
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist());
    }

    @Test
    void listMerchantPaymentsByState_nextToken_delegatesToService() throws Exception {
        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("COMPLETED"), isNull(), eq(true), eq("token-2")))
                .thenReturn(new MerchantPaymentsPage(List.of(), null));

        mvc.perform(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?scanIndexForward=true&nextToken=token-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextToken").doesNotExist());

        verify(merchantPaymentQueryService).listMerchantPaymentsByState(
                eq("merch_1"), eq("COMPLETED"), isNull(), eq(true), eq("token-2"));
    }

    @Test
    void listMerchantPaymentsByState_invalidNextToken_returns400() throws Exception {
        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("COMPLETED"), isNull(), isNull(), eq("bad-token")))
                .thenThrow(new InvalidPaginationTokenException("bad-token"));

        MvcResult result = mvc.perform(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?nextToken=bad-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"))
                .andExpect(jsonPath("$.message").value("Invalid pagination token"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void listMerchantPaymentsByState_crossGsiToken_returns400() throws Exception {
        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("COMPLETED"), isNull(), isNull(), eq("gsi-merchant-payments-token")))
                .thenThrow(new InvalidPaginationTokenException("gsi-merchant-payments-token"));

        mvc.perform(get("/api/v1/merchants/merch_1/payments/state/COMPLETED?nextToken=gsi-merchant-payments-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_TOKEN"));
    }

    @Test
    void listMerchantPaymentsByState_invalidState_returns400() throws Exception {
        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("BOGUS"), isNull(), isNull(), isNull()))
                .thenThrow(new InvalidPaymentStateException("BOGUS"));

        MvcResult result = mvc.perform(get("/api/v1/merchants/merch_1/payments/state/BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAYMENT_STATE"))
                .andExpect(jsonPath("$.message").value("Invalid payment state: BOGUS"))
                .andReturn();

        JsonPathSupport.readInstantAssertingPlausibleNow(result.getResponse().getContentAsString(), "$.timestamp");
    }

    @Test
    void listMerchantPaymentsByState_empty_returns200WithEmptyItems() throws Exception {
        when(merchantPaymentQueryService.listMerchantPaymentsByState(
                eq("merch_1"), eq("RECEIVED"), isNull(), isNull(), isNull()))
                .thenReturn(new MerchantPaymentsPage(List.of(), null));

        mvc.perform(get("/api/v1/merchants/merch_1/payments/state/RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextToken").doesNotExist());
    }
}
