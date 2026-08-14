package com.bhukkad.payment;

import com.bhukkad.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.razorpay.enabled", havingValue = "true")
public class RazorpayPaymentGateway implements PaymentGateway {

    private final PaymentProperties paymentProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create("https://api.razorpay.com");

    @Override
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "createOrderFallback")
    public GatewayOrderResult createOrder(GatewayOrderRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", toPaise(request.amount()));
        body.put("currency", paymentProperties.getRazorpay().getCurrency());
        body.put("receipt", request.receipt());
        body.put("payment_capture", 1);

        JsonNode response = post("/v1/orders", body);
        return GatewayOrderResult.builder()
                .gatewayOrderId(response.path("id").asText())
                .rawResponse(response.toString())
                .build();
    }

    @Override
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "capturePaymentFallback")
    public GatewayPaymentResult capturePayment(GatewayCaptureRequest request) {
        JsonNode response = get("/v1/orders/" + request.gatewayOrderId() + "/payments");
        JsonNode items = response.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new BusinessException("No Razorpay payment found for order " + request.gatewayOrderId());
        }
        JsonNode payment = items.get(0);
        return GatewayPaymentResult.builder()
                .gatewayPaymentId(payment.path("id").asText())
                .transactionId(payment.path("id").asText())
                .success("captured".equalsIgnoreCase(payment.path("status").asText()))
                .rawResponse(payment.toString())
                .build();
    }

    @Override
    @CircuitBreaker(name = "paymentGateway", fallbackMethod = "refundPaymentFallback")
    public GatewayRefundResult refundPayment(GatewayRefundRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", toPaise(request.amount()));

        JsonNode response = post("/v1/payments/" + request.gatewayPaymentId() + "/refund", body);
        return GatewayRefundResult.builder()
                .refundId(response.path("id").asText())
                .success("processed".equalsIgnoreCase(response.path("status").asText()))
                .rawResponse(response.toString())
                .build();
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (!StringUtils.hasText(signature)) {
            return false;
        }
        try {
            String secret = paymentProperties.getRazorpay().getWebhookSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = bytesToHex(hash);
            return expected.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.warn("Razorpay webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            String response = restClient.post()
                    .uri(path)
                    .headers(this::authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new BusinessException("Razorpay request failed: " + e.getMessage());
        }
    }

    private JsonNode get(String path) {
        try {
            String response = restClient.get()
                    .uri(path)
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new BusinessException("Razorpay request failed: " + e.getMessage());
        }
    }

    private void authHeaders(HttpHeaders headers) {
        PaymentProperties.Razorpay razorpay = paymentProperties.getRazorpay();
        String credentials = razorpay.getKeyId() + ":" + razorpay.getKeySecret();
        headers.setBasicAuth(razorpay.getKeyId(), razorpay.getKeySecret());
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
    }

    private static int toPaise(double amount) {
        return (int) Math.round(amount * 100);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private GatewayOrderResult createOrderFallback(GatewayOrderRequest request, Throwable cause) {
        throw new BusinessException("Payment gateway is temporarily unavailable. Please try again.");
    }

    @SuppressWarnings("unused")
    private GatewayPaymentResult capturePaymentFallback(GatewayCaptureRequest request, Throwable cause) {
        throw new BusinessException("Payment gateway is temporarily unavailable. Please try again.");
    }

    @SuppressWarnings("unused")
    private GatewayRefundResult refundPaymentFallback(GatewayRefundRequest request, Throwable cause) {
        throw new BusinessException("Payment gateway is temporarily unavailable. Please try again.");
    }
}
