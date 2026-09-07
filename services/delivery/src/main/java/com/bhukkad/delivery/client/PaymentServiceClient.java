package com.bhukkad.delivery.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Client for calling payment service APIs for COD wallet and rider earnings.
 * These operations are owned by the payment service but called from delivery.
 *
 * <p>Carries a service JWT on the {@code X-Service-Token} header so the payment
 * service's {@code ServiceJwtAuthFilter} authorizes the mesh call.</p>
 */
@Slf4j
@Component
public class PaymentServiceClient {

    private final RestTemplate restTemplate;
    private final String paymentServiceUrl;
    private final ServiceJwtAuthTokenProvider authTokenProvider;

    public PaymentServiceClient(RestTemplate restTemplate,
                                @Value("${app.services.payment.url:http://payment:8080}") String paymentServiceUrl,
                                ServiceJwtAuthTokenProvider authTokenProvider) {
        this.restTemplate = restTemplate;
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
            return restTemplate.getForObject(
                    paymentServiceUrl + "/api/v1/internal/delivery/cod-wallet/{agentId}",
                    Map.class,
                    agentId);
        } catch (RestClientException e) {
            log.error("Failed to get COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> creditCodWallet(Long agentId, BigDecimal amount) {
        try {
            String url = paymentServiceUrl + "/api/v1/internal/delivery/cod-wallet/{agentId}/credit?amount={amount}";
            HttpEntity<?> request = new HttpEntity<>(authHeaders());
            return restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    Map.class,
                    agentId,
                    amount).getBody();
        } catch (RestClientException e) {
            log.error("Failed to credit COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> debitCodWallet(Long agentId, BigDecimal amount) {
        try {
            String url = paymentServiceUrl + "/api/v1/internal/delivery/cod-wallet/{agentId}/debit?amount={amount}";
            HttpEntity<?> request = new HttpEntity<>(authHeaders());
            return restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    Map.class,
                    agentId,
                    amount).getBody();
        } catch (RestClientException e) {
            log.error("Failed to debit COD wallet for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    // ============= Rider Earnings Operations =============

    @SuppressWarnings("unchecked")
    public Map<String, Object> recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(
                    paymentServiceUrl + "/api/v1/internal/delivery/earnings/record")
                    .queryParam("agentId", agentId)
                    .queryParam("orderId", orderId)
                    .queryParam("amount", amount)
                    .toUriString();
            HttpEntity<?> request = new HttpEntity<>(authHeaders());
            return restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    Map.class).getBody();
        } catch (RestClientException e) {
            log.error("Failed to record earning for agent {} order {}: {}", agentId, orderId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getEarnings(Long agentId) {
        try {
            return restTemplate.getForObject(
                    paymentServiceUrl + "/api/v1/internal/delivery/earnings/{agentId}",
                    List.class,
                    agentId);
        } catch (RestClientException e) {
            log.error("Failed to get earnings for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }

    public Map<String, Object> markEarningPaid(Long earningId) {
        try {
            String url = paymentServiceUrl + "/api/v1/internal/delivery/earnings/{earningId}/mark-paid";
            HttpEntity<?> request = new HttpEntity<>(authHeaders());
            return restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    request,
                    Map.class,
                    earningId).getBody();
        } catch (RestClientException e) {
            log.error("Failed to mark earning {} as paid: {}", earningId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }
}
