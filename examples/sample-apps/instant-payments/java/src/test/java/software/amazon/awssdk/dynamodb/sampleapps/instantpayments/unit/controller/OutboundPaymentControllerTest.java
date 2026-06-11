package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.unit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.controller.OutboundPaymentController;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentRequest;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.CreateOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.GetOutboundPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentCreationResult;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.PaymentEventResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.dto.ProcessPaymentResponse;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.GlobalExceptionHandler;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.config.WebMvcAsyncConfig;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.IdempotencyConflictException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.exception.PaymentNotFoundException;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.mapper.PaymentMapper;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.Payment;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.model.PaymentState;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentProcessor;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentQueryService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.service.OutboundPaymentService;
import software.amazon.awssdk.dynamodb.sampleapps.instantpayments.support.AsyncMockMvcTestSupport;

/**
 * Web MVC slice tests for {@link OutboundPaymentController}, covering create, process, and get
 * endpoints plus validation and exception handling through {@link GlobalExceptionHandler}.
 */
@Tag("unit")
@WebMvcTest(OutboundPaymentController.class)
@Import({GlobalExceptionHandler.class, WebMvcAsyncConfig.class})
public class OutboundPaymentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private OutboundPaymentService paymentService;

    @MockitoBean
    private OutboundPaymentQueryService paymentQueryService;

    @MockitoBean
    private OutboundPaymentProcessor paymentProcessor;

    @MockitoBean
    private PaymentMapper paymentMapper;

    @Test
    void createOutboundPayment_whenValidRequest_shouldReturn201() throws Exception {
        when(paymentService.createOutboundPayment(any(CreateOutboundPaymentRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new PaymentCreationResult(
                        new CreateOutboundPaymentResponse("pay_1", "RECEIVED", "corr_1", Instant.now()),
                        true)));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "idem-1",
                                  "merchantId": "merch_1",
                                  "debtorAccountId": "acc_usd_1",
                                  "creditorIban": "RO49AAAA1B31007593840000",
                                  "creditorName": "John",
                                  "amount": 10,
                                  "currency": "USD"
                                }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value("pay_1"));
    }

    @Test
    void createOutboundPayment_whenIdempotentRetry_shouldReturn200() throws Exception {
        when(paymentService.createOutboundPayment(any(CreateOutboundPaymentRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(new PaymentCreationResult(
                        new CreateOutboundPaymentResponse("pay_retry", "RECEIVED", "corr_r", Instant.now()),
                        false)));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "idem-retry",
                                  "merchantId": "merch_1",
                                  "debtorAccountId": "acc_usd_1",
                                  "creditorIban": "RO49AAAA1B31007593840000",
                                  "creditorName": "Jane",
                                  "amount": 25,
                                  "currency": "USD"
                                }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("pay_retry"));
    }

    @Test
    void createOutboundPayment_whenIdempotencyConflict_shouldReturn409() throws Exception {
        when(paymentService.createOutboundPayment(any(CreateOutboundPaymentRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IdempotencyConflictException("idem-clash")));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "idem-clash",
                                  "merchantId": "merch_1",
                                  "debtorAccountId": "acc_usd_1",
                                  "creditorIban": "RO49AAAA1B31007593840000",
                                  "creditorName": "Joe",
                                  "amount": 99,
                                  "currency": "USD"
                                }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void createOutboundPayment_whenInvalidRequest_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/payments/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "",
                                  "merchantId": "merch_1",
                                  "debtorAccountId": "acc_usd_1",
                                  "creditorIban": "RO49AAAA1B31007593840000",
                                  "creditorName": "Test",
                                  "amount": 10,
                                  "currency": "USD"
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("idempotencyKey: must not be blank"));
    }

    @Test
    void processPayment_whenPaymentMissing_shouldReturn404() throws Exception {
        when(paymentProcessor.processPayment("missing"))
                .thenReturn(CompletableFuture.failedFuture(new PaymentNotFoundException("missing")));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound/missing/process"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void processPayment_whenUnexpectedError_shouldReturn500() throws Exception {
        when(paymentProcessor.processPayment("pay_bad"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("downstream failure")));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound/pay_bad/process"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"));
    }

    @Test
    void processPayment_whenValidRequest_shouldReturn200() throws Exception {
        Payment payment = new Payment();
        payment.setPaymentId("pay_ok");
        payment.setState(PaymentState.COMPLETED.name());
        payment.setReasonCode(null);

        when(paymentProcessor.processPayment("pay_ok"))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(paymentProcessor.getPayment("pay_ok"))
                .thenReturn(CompletableFuture.completedFuture(payment));
        when(paymentMapper.toProcessPaymentResponse(payment))
                .thenReturn(new ProcessPaymentResponse("pay_ok", "COMPLETED", null));

        AsyncMockMvcTestSupport.performAsync(mvc, post("/api/v1/payments/outbound/pay_ok/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("pay_ok"))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.reasonCode").isEmpty());
    }

    @Test
    void getOutboundPayment_whenPaymentMissing_shouldReturn404() throws Exception {
        when(paymentQueryService.getOutboundPayment("pay_missing"))
                .thenReturn(CompletableFuture.failedFuture(new PaymentNotFoundException("pay_missing")));

        AsyncMockMvcTestSupport.performAsync(mvc, get("/api/v1/payments/outbound/pay_missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void getOutboundPayment_whenPaymentIdMalformed_shouldReturn400() throws Exception {
        mvc.perform(get("/api/v1/payments/outbound/pay$bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getOutboundPayment_whenPaymentIdTooLong_shouldReturn400() throws Exception {
        String tooLong = "a".repeat(65);
        mvc.perform(get("/api/v1/payments/outbound/" + tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void processPayment_whenPaymentIdMalformed_shouldReturn400() throws Exception {
        mvc.perform(post("/api/v1/payments/outbound/pay$bad/process"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void getOutboundPayment_whenPaymentExists_shouldReturn200WithEvents() throws Exception {
        Instant t0 = Instant.parse("2025-01-01T12:00:00Z");
        Instant t1 = Instant.parse("2025-01-01T12:01:00Z");
        when(paymentQueryService.getOutboundPayment("pay_get")).thenReturn(CompletableFuture.completedFuture(
                new GetOutboundPaymentResponse(
                "pay_get",
                "COMPLETED",
                "corr_x",
                t0,
                t1,
                "acc_usd_1",
                "RO49AAAA1B31007593840000",
                "Jane",
                new BigDecimal("42.00"),
                "USD",
                "idem_get",
                null,
                3,
                List.of(
                        new PaymentEventResponse(
                                "EVENT#0000000000000000001",
                                "OUTBOUND_PAYMENT_CREATED",
                                null,
                                "corr_x"),
                        new PaymentEventResponse(
                                "EVENT#0000000000000000002",
                                "FUNDS_RESERVED",
                                null,
                                "corr_x")))));

        AsyncMockMvcTestSupport.performAsync(mvc, get("/api/v1/payments/outbound/pay_get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("pay_get"))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].eventType").value("OUTBOUND_PAYMENT_CREATED"))
                .andExpect(jsonPath("$.events[1].eventType").value("FUNDS_RESERVED"));
    }
}
