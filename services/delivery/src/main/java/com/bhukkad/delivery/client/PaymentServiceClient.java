package com.bhukkad.delivery.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Client for calling payment service APIs for COD wallet and rider earnings.
 * These operations are owned by the payment service but called from delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentServiceClient {

    private final RestTemplate restTemplate;

    @Value("${app.services.payment.url:http://payment:8093}")
    private String paymentServiceUrl;

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
            return restTemplate.exchange(
                    paymentServiceUrl + "/api/v1/internal/delivery/cod-wallet/{agentId}/credit?amount={amount}",
                    HttpMethod.POST,
                    HttpEntity.EMPTY,
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
            return restTemplate.exchange(
                    paymentServiceUrl + "/api/v1/internal/delivery/cod-wallet/{agentId}/debit?amount={amount}",
                    HttpMethod.POST,
                    HttpEntity.EMPTY,
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
            return restTemplate.postForObject(
                    paymentServiceUrl + "/api/v1/internal/delivery/earnings/record?agentId={agentId}&orderId={orderId}&amount={amount}",
                    null,
                    Map.class,
                    agentId,
                    orderId,
                    amount);
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
            return restTemplate.postForObject(
                    paymentServiceUrl + "/api/v1/internal/delivery/earnings/{earningId}/mark-paid",
                    null,
                    Map.class,
                    earningId);
        } catch (RestClientException e) {
            log.error("Failed to mark earning {} as paid: {}", earningId, e.getMessage());
            throw new RuntimeException("Payment service unavailable", e);
        }
    }
}
