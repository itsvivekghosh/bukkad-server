package com.bhukkad.commerce.delivery.infrastructure.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Client for calling payment service APIs for COD wallet and rider earnings.
 * These operations are owned by the payment service but called from delivery.
 *
 * <p>Carries a service JWT on the {@code X-Service-Token} header so the payment
 * service's {@code ServiceJwtAuthFilter} authorizes the mesh call.</p>
 *
 * <p>Uses WebClient (non-blocking) instead of RestTemplate to avoid pinning
 * servlet/virtual threads during external RPC (audit CR-04).</p>
 */
@Slf4j
@Component("deliveryPaymentServiceClient")
public class PaymentServiceClient {

    private final WebClient webClient;
    private final String paymentServiceUrl;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public PaymentServiceClient(MeterRegistry meterRegistry,
                                @Value("${app.services.payment.url:http://payment:8080}") String paymentServiceUrl,
                                ServiceJwtAuthTokenProvider authTokenProvider) {
        this.webClient = PlatformWebClientBuilderFactory.forTarget("payment-service", meterRegistry)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .responseTimeout(java.time.Duration.ofSeconds(10))
                .build(paymentServiceUrl);
        this.paymentServiceUrl = paymentServiceUrl;
        this.authTokenProvider = authTokenProvider;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = authTokenProvider.serviceToken();
        if (token != null) {
            headers.set("X-Service-Token", token);
        }
        return headers;
    }

    // ============= COD Wallet Operations =============

    public Map<String, Object> getCodWallet(Long agentId) {
        try {
            return webClient.get()
                    .uri("/api/v1/internal/delivery/cod-wallet/{agentId}", agentId)
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to get COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> creditCodWallet(Long agentId, BigDecimal amount) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/delivery/cod-wallet/{agentId}/credit?amount={amount}", agentId, amount)
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to credit COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> debitCodWallet(Long agentId, BigDecimal amount) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/delivery/cod-wallet/{agentId}/debit?amount={amount}", agentId, amount)
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to debit COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    // ============= Rider Earnings Operations =============

    @SuppressWarnings("unchecked")
    public Map<String, Object> recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        try {
            return webClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/internal/delivery/earnings/record")
                            .queryParam("agentId", agentId)
                            .queryParam("orderId", orderId)
                            .queryParam("amount", amount)
                            .build())
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to record earning for agent {} order {}: {}", agentId, orderId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEarnings(Long agentId) {
        try {
            List<Map<String, Object>> body = webClient.get()
                    .uri("/api/v1/internal/delivery/earnings/{agentId}", agentId)
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(java.time.Duration.ofSeconds(10));
            return body != null ? body : List.of();
        } catch (Exception e) {
            log.error("Failed to get earnings for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> markEarningPaid(Long earningId) {
        try {
            return webClient.post()
                    .uri("/api/v1/internal/delivery/earnings/{earningId}/mark-paid", earningId)
                    .headers(h -> h.addAll(authHeaders()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception e) {
            log.error("Failed to mark earning {} as paid: {}", earningId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }
}
