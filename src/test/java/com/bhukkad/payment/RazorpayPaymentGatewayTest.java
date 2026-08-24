package com.bhukkad.payment;

import com.bhukkad.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static com.bhukkad.payment.PaymentGateway.GatewayCaptureRequest;
import static com.bhukkad.payment.PaymentGateway.GatewayOrderRequest;
import static com.bhukkad.payment.PaymentGateway.GatewayOrderResult;
import static com.bhukkad.payment.PaymentGateway.GatewayPaymentResult;
import static com.bhukkad.payment.PaymentGateway.GatewayRefundRequest;
import static com.bhukkad.payment.PaymentGateway.GatewayRefundResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RazorpayPaymentGatewayTest {

    @Mock
    private PaymentProperties paymentProperties;

    @Mock
    private RestClient restClient;

    @Mock
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.RequestBodySpec requestBodySpec;

    @Mock
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private RestClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ObjectMapper objectMapper;
    private RazorpayPaymentGateway gateway;

    private PaymentProperties.Razorpay razorpay;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        razorpay = new PaymentProperties.Razorpay();
        razorpay.setKeyId("key_id");
        razorpay.setKeySecret("key_secret");
        razorpay.setWebhookSecret("webhook_secret");
        razorpay.setCurrency("INR");

        when(paymentProperties.getRazorpay()).thenReturn(razorpay);

        gateway = new RazorpayPaymentGateway(paymentProperties, objectMapper);
        ReflectionTestUtils.setField(gateway, "restClient", restClient);
    }

    @Test
    void createOrder_success() throws Exception {
        String jsonResponse = "{\"id\":\"order_123\",\"status\":\"created\"}";
        mockPostResponse(jsonResponse);

        GatewayOrderResult result = gateway.createOrder(
                GatewayOrderRequest.builder().amount(100.0).receipt("receipt_1").build());

        assertNotNull(result);
        assertEquals("order_123", result.gatewayOrderId());
        assertTrue(result.rawResponse().contains("order_123"));
    }

    @Test
    void createOrder_throwsBusinessExceptionOnFailure() throws Exception {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/orders")).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("Network error"));

        assertThrows(BusinessException.class, () ->
                gateway.createOrder(GatewayOrderRequest.builder().amount(100.0).receipt("r1").build()));
    }

    @Test
    void capturePayment_success() throws Exception {
        String jsonResponse = "{\"items\":[{\"id\":\"pay_123\",\"status\":\"captured\"}]}";
        mockGetResponse(jsonResponse);

        GatewayCaptureRequest request = GatewayCaptureRequest.builder()
                .gatewayOrderId("order_123")
                .amount(100.0)
                .build();

        GatewayPaymentResult result = gateway.capturePayment(request);

        assertNotNull(result);
        assertEquals("pay_123", result.gatewayPaymentId());
        assertTrue(result.success());
    }

    @Test
    void capturePayment_noPayments_throwsBusinessException() throws Exception {
        String jsonResponse = "{\"items\":[]}";
        mockGetResponse(jsonResponse);

        GatewayCaptureRequest request = GatewayCaptureRequest.builder()
                .gatewayOrderId("order_123")
                .amount(100.0)
                .build();

        assertThrows(BusinessException.class, () -> gateway.capturePayment(request));
    }

    @Test
    void capturePayment_notCaptured() throws Exception {
        String jsonResponse = "{\"items\":[{\"id\":\"pay_123\",\"status\":\"failed\"}]}";
        mockGetResponse(jsonResponse);

        GatewayCaptureRequest request = GatewayCaptureRequest.builder()
                .gatewayOrderId("order_123")
                .amount(100.0)
                .build();

        GatewayPaymentResult result = gateway.capturePayment(request);

        assertFalse(result.success());
    }

    @Test
    void capturePayment_throwsBusinessExceptionOnFailure() throws Exception {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri("/v1/orders/order_123/payments")).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.headers(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("API error"));

        GatewayCaptureRequest request = GatewayCaptureRequest.builder()
                .gatewayOrderId("order_123")
                .amount(100.0)
                .build();

        assertThrows(BusinessException.class, () -> gateway.capturePayment(request));
    }

    @Test
    void refundPayment_success() throws Exception {
        String jsonResponse = "{\"id\":\"ref_123\",\"status\":\"processed\"}";
        mockPostResponse(jsonResponse);

        GatewayRefundResult result = gateway.refundPayment(GatewayRefundRequest.builder()
                .gatewayPaymentId("pay_123")
                .amount(50.0)
                .build());

        assertNotNull(result);
        assertEquals("ref_123", result.refundId());
        assertTrue(result.success());
    }

    @Test
    void refundPayment_notProcessed() throws Exception {
        String jsonResponse = "{\"id\":\"ref_123\",\"status\":\"pending\"}";
        mockPostResponse(jsonResponse);

        GatewayRefundResult result = gateway.refundPayment(GatewayRefundRequest.builder()
                .gatewayPaymentId("pay_123")
                .amount(50.0)
                .build());

        assertFalse(result.success());
    }

    @Test
    void refundPayment_throwsBusinessExceptionOnFailure() throws Exception {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/payments/pay_123/refund")).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RuntimeException("Refund failed"));

        assertThrows(BusinessException.class, () ->
                gateway.refundPayment(GatewayRefundRequest.builder()
                        .gatewayPaymentId("pay_123")
                        .amount(50.0)
                        .build()));
    }

    @Test
    void verifyWebhookSignature_validSignature_returnsTrue() {
        String payload = "{\"event\":\"payment.captured\"}";
        // HMAC-SHA256 of payload using webhookSecret "webhook_secret"
        assertTrue(gateway.verifyWebhookSignature(payload,
                "63482aecf393ec15e418daab94dffe6cd7a1ddeec5ade268106920e9f1c8363d"));
    }

    @Test
    void verifyWebhookSignature_invalidSignature_returnsFalse() {
        String payload = "{\"event\":\"payment.captured\"}";
        assertFalse(gateway.verifyWebhookSignature(payload, "invalid_signature"));
    }

    @Test
    void verifyWebhookSignature_emptySignature_returnsFalse() {
        assertFalse(gateway.verifyWebhookSignature("payload", ""));
    }

    @Test
    void verifyWebhookSignature_nullSignature_returnsFalse() {
        assertFalse(gateway.verifyWebhookSignature("payload", null));
    }

    @Test
    void verifyWebhookSignature_exceptionDuringVerification_returnsFalse() {
        razorpay.setWebhookSecret(null);
        assertFalse(gateway.verifyWebhookSignature("payload", "some_signature"));
    }

    private void mockPostResponse(String json) throws Exception {
        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(json);
    }

    private void mockGetResponse(String json) throws Exception {
        when(restClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.headers(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(json);
    }

    // Fallback methods (invoked only by Resilience4j @CircuitBreaker at runtime) are
    // exercised through their public entry points above; the failure-path tests cover
    // the BusinessException thrown when the RestClient call fails.
}